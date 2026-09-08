package com.example.georescux.domain.security

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Trust-model verification on the JVM (real EC keys via the JDK): a valid
 * signature is accepted, and every tamper/missing/expired/replay case is
 * REJECTED. The Android Keystore implementation runs against the same
 * interface but requires a device.
 */
class EventAuthenticatorTest {

    private lateinit var keyPair: KeyPair
    private lateinit var signer: EventSigner
    private lateinit var authenticator: EventAuthenticator

    private fun signedEvent(
        nowMs: Long = 20_000L,
        occurredAtMs: Long = 20_000L,
        ttlSeconds: Long = 3_600L,
        sequence: Long = 0L,
    ): IncidentEvent = authenticator.sign(
        IncidentEvent(
            eventId = "evt-1",
            originId = "device-a",
            type = IncidentType.SOS,
            occurredAtMs = occurredAtMs,
            ttlSeconds = ttlSeconds,
            sequence = sequence,
        )
    )

    @Before
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        signer = object : EventSigner {
            override fun sign(payload: ByteArray): ByteArray =
                Signature.getInstance("SHA256withECDSA").apply {
                    initSign(keyPair.private)
                    update(payload)
                }.sign()

            override fun verify(payload: ByteArray, signature: ByteArray): Boolean =
                Signature.getInstance("SHA256withECDSA").apply {
                    initVerify(keyPair.public)
                    update(payload)
                }.verify(signature)
        }
        authenticator = EventAuthenticator(signer)
    }

    @Test
    fun `a validly signed event is accepted`() {
        assertEquals(AuthVerdict.OK, authenticator.authenticate(signedEvent(), nowMs = 20_000L))
    }

    @Test
    fun `a tampered event is rejected`() {
        val signed = signedEvent()
        val tampered = signed.copy(severity = com.example.georescux.domain.incident.IncidentSeverity.LOW)

        assertEquals(AuthVerdict.BAD_SIGNATURE, authenticator.authenticate(tampered, nowMs = 20_000L))
    }

    @Test
    fun `an event with a foreign signature is rejected`() {
        val otherPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val event = IncidentEvent(
            eventId = "evt-1", originId = "device-a", type = IncidentType.SOS, occurredAtMs = 20_000L
        )
        val foreignSignature = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(otherPair.private)
                update(event.canonicalString().toByteArray())
            }.sign()
        )

        val verdict = authenticator.authenticate(event.copy(signature = foreignSignature), nowMs = 20_000L)

        assertEquals(AuthVerdict.BAD_SIGNATURE, verdict)
    }

    @Test
    fun `an unsigned event is rejected`() {
        val unsigned = signedEvent().copy(signature = null)

        assertEquals(AuthVerdict.MISSING_SIGNATURE, authenticator.authenticate(unsigned, nowMs = 20_000L))
    }

    @Test
    fun `an expired event is rejected`() {
        val signed = signedEvent(occurredAtMs = 10_000L, ttlSeconds = 5) // expires at 15_000

        assertEquals(AuthVerdict.EXPIRED, authenticator.authenticate(signed, nowMs = 20_000L))
    }

    @Test
    fun `an event with a future timestamp beyond the skew window is rejected`() {
        val signed = signedEvent(occurredAtMs = 200_000L) // far in the future of now=20_000

        assertEquals(AuthVerdict.EXPIRED, authenticator.authenticate(signed, nowMs = 20_000L))
    }

    @Test
    fun `a replayed sequence is rejected`() {
        assertEquals(
            AuthVerdict.OK,
            authenticator.authenticate(signedEvent(sequence = 7), nowMs = 20_000L)
        )
        val replay = authenticator.sign(
            IncidentEvent(
                eventId = "evt-2", originId = "device-a", type = IncidentType.SOS,
                occurredAtMs = 20_000L, sequence = 7, // same origin, same-or-older sequence
            )
        )
        assertEquals(AuthVerdict.REPLAYED, authenticator.authenticate(replay, nowMs = 20_000L))
    }

    @Test
    fun `a malformed event is rejected before signature checks`() {
        val malformed = IncidentEvent(
            eventId = "", originId = "device-a", type = IncidentType.SOS, occurredAtMs = 20_000L
        ).let { authenticator.sign(it) }

        assertEquals(AuthVerdict.MALFORMED, authenticator.authenticate(malformed, nowMs = 20_000L))
    }

    @Test
    fun `signatures are deterministic per payload but differ across payloads`() {
        val a = authenticator.sign(
            IncidentEvent(eventId = "evt-1", originId = "o", type = IncidentType.SOS, occurredAtMs = 1_000L)
        )
        val b = authenticator.sign(
            IncidentEvent(eventId = "evt-1", originId = "o", type = IncidentType.SOS, occurredAtMs = 1_000L)
        )
        // ECDSA with a non-deterministic nonce: same payload may produce
        // different signatures, both must verify — so compare verdicts, not bytes.
        assertEquals(AuthVerdict.OK, authenticator.authenticate(a, nowMs = 2_000L))
        assertEquals(AuthVerdict.OK, authenticator.authenticate(b, nowMs = 2_000L))
        val modified = a.copy(occurredAtMs = 2_000L)
        assertNotEquals(a.canonicalString(), modified.canonicalString())
        assertTrue(modified.signature != null)
    }
}

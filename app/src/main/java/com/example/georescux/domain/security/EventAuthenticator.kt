package com.example.georescux.domain.security

import com.example.georescux.domain.incident.IncidentEvent

/**
 * Pure verification of incoming events: malformed events, missing
 * signatures, tampered payloads and expired events all fail closed.
 * Fully JVM-testable — tests inject JDK EC key pairs through the same
 * [EventSigner] interface the Keystore implementation uses on device.
 *
 * Replay protection: per-origin sequence numbers are enforced by
 * [ReplayGuard] (monotonic per origin); unordered events (sequence 0)
 * rely on eventId deduplication downstream and TTL for bounded lifetime.
 */
enum class AuthVerdict { OK, MALFORMED, MISSING_SIGNATURE, BAD_SIGNATURE, EXPIRED, REPLAYED }

class ReplayGuard {
    private val lastSequenceByOrigin = HashMap<String, Long>()

    /** Accepts only monotonic sequences per origin; sequence 0 = unordered, always accepted. */
    @Synchronized
    fun accept(originId: String, sequence: Long): Boolean {
        if (sequence <= 0) return true
        val last = lastSequenceByOrigin[originId] ?: Long.MIN_VALUE
        if (sequence <= last) return false
        lastSequenceByOrigin[originId] = sequence
        return true
    }
}

class EventAuthenticator(
    private val signer: EventSigner,
    private val replayGuard: ReplayGuard = ReplayGuard(),
    private val skewMs: Long = 60_000L,
) {

    /** Signs the canonical form of [event] and returns a copy carrying the signature. */
    fun sign(event: IncidentEvent): IncidentEvent {
        val signature = Base64Codec.encode(signer.sign(event.canonicalString().toByteArray()))
        return event.copy(signature = signature)
    }

    fun authenticate(event: IncidentEvent, nowMs: Long): AuthVerdict {
        if (event.eventId.isBlank() || event.originId.isBlank() || event.occurredAtMs <= 0 || event.ttlSeconds < 0) {
            return AuthVerdict.MALFORMED
        }
        val signature = event.signature
        if (signature.isNullOrBlank()) return AuthVerdict.MISSING_SIGNATURE
        val valid = try {
            signer.verify(event.canonicalString().toByteArray(), Base64Codec.decode(signature))
        } catch (e: IllegalArgumentException) {
            false // undecodable Base64 = tampered/foreign message
        }
        if (!valid) return AuthVerdict.BAD_SIGNATURE
        val expiresAtMs = event.occurredAtMs + event.ttlSeconds * 1000
        if (event.occurredAtMs > nowMs + skewMs || expiresAtMs < nowMs) return AuthVerdict.EXPIRED
        if (!replayGuard.accept(event.originId, event.sequence)) return AuthVerdict.REPLAYED
        return AuthVerdict.OK
    }
}

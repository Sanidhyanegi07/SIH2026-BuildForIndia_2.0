package com.example.georescux.domain.relay

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the relay core guarantees WITHOUT any radio transport: duplicate
 * suppression, TTL expiry, malformed rejection, no repeated forwarding,
 * unavailable-peer outbox + reconnect flush, and out-of-order sequence
 * handling. Real BLE/Wi-Fi-Direct behavior is explicitly NOT covered here.
 */
class RelayEngineTest {

    private class FakeTransport : RelayTransport {
        val sent = mutableListOf<Pair<String, String>>() // peer to payload
        var peers: List<String> = listOf("peer-1", "peer-2")
        var failFor = mutableSetOf<String>()

        override fun availablePeers(): List<String> = peers
        override fun send(peerId: String, payload: String): Boolean {
            if (peerId in failFor) return false
            sent.add(peerId to payload)
            return true
        }
    }

    private fun event(
        id: String = "evt-1",
        origin: String = "device-a",
        occurredAtMs: Long = 10_000L,
        ttlSeconds: Long = 3_600L,
        sequence: Long = 0L,
    ) = IncidentEvent(
        eventId = id,
        originId = origin,
        type = IncidentType.SOS,
        occurredAtMs = occurredAtMs,
        ttlSeconds = ttlSeconds,
        sequence = sequence,
    )

    private fun engine(
        transport: FakeTransport = FakeTransport(),
        selfOrigin: String = "device-self",
        now: Long = 20_000L,
    ) = RelayEngine(selfOriginId = selfOrigin, transport = transport, nowMs = { now })

    @Test
    fun `a received event is forwarded to peers exactly once`() {
        val transport = FakeTransport()
        val relay = engine(transport)

        val decision = relay.onEventReceived(RelayEnvelope(event(), "payload-a", fromPeerId = "peer-1"))

        assertEquals(RelayDecision.ACCEPTED_FORWARDED, decision)
        assertEquals(listOf("peer-2" to "payload-a"), transport.sent) // sender excluded
    }

    @Test
    fun `a duplicate event is never forwarded twice`() {
        val transport = FakeTransport()
        val relay = engine(transport)
        relay.onEventReceived(RelayEnvelope(event(), "payload-a", fromPeerId = "peer-1"))

        val second = relay.onEventReceived(RelayEnvelope(event(), "payload-a", fromPeerId = "peer-2"))

        assertEquals(RelayDecision.DUPLICATE, second)
        assertEquals(1, transport.sent.size) // still only the first forward
    }

    @Test
    fun `an expired event is rejected and never forwarded`() {
        val transport = FakeTransport()
        val relay = engine(transport, now = 20_000L)

        val decision = relay.onEventReceived(
            RelayEnvelope(event(occurredAtMs = 10_000L, ttlSeconds = 5), "payload", fromPeerId = "peer-1")
        )

        assertEquals(RelayDecision.EXPIRED, decision)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `a malformed event is rejected`() {
        val transport = FakeTransport()
        val relay = engine(transport)

        assertEquals(RelayDecision.MALFORMED, relay.onEventReceived(RelayEnvelope(event(id = "  "), "p")))
        assertEquals(RelayDecision.MALFORMED, relay.onEventReceived(RelayEnvelope(event(occurredAtMs = 0), "p")))
        assertEquals(RelayDecision.MALFORMED, relay.onEventReceived(RelayEnvelope(event(ttlSeconds = -1), "p")))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `an event originating from this device is not re-accepted from a peer`() {
        val transport = FakeTransport()
        val relay = engine(transport, selfOrigin = "device-self")

        val decision = relay.onEventReceived(
            RelayEnvelope(event(origin = "device-self"), "payload", fromPeerId = "peer-1")
        )

        assertEquals(RelayDecision.OWN_EVENT, decision)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `a failing peer goes to the outbox and is flushed exactly once on reconnect`() {
        val transport = FakeTransport().apply { failFor = mutableSetOf("peer-2") }
        val relay = engine(transport)
        relay.onLocalEvent(event()) // peer-1 succeeds, peer-2 fails
        assertEquals(1, transport.sent.size)
        assertEquals(1, relay.pendingCountFor("peer-2"))

        transport.failFor.clear()
        val delivered = relay.onPeerAvailable("peer-2")

        assertEquals(1, delivered)
        assertEquals(0, relay.pendingCountFor("peer-2"))
        assertEquals(2, transport.sent.size)
        // Repeated reconnects must not resend (no duplicate forwarding).
        assertEquals(0, relay.onPeerAvailable("peer-2"))
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun `an out-of-order sequence is rejected while a newer one is accepted`() {
        val transport = FakeTransport()
        val relay = engine(transport)

        assertEquals(
            RelayDecision.ACCEPTED_FORWARDED,
            relay.onEventReceived(RelayEnvelope(event(id = "e2", sequence = 2), "p2", fromPeerId = "peer-1"))
        )
        assertEquals(
            RelayDecision.STALE_SEQUENCE,
            relay.onEventReceived(RelayEnvelope(event(id = "e1", sequence = 1), "p1", fromPeerId = "peer-1"))
        )
        assertEquals(1, transport.sent.size) // only the newer event was forwarded
    }

    @Test
    fun `a local event with no reachable peers is queued not lost`() {
        val transport = FakeTransport().apply { peers = emptyList() }
        val relay = engine(transport)

        val decision = relay.onLocalEvent(event())

        assertEquals(RelayDecision.ACCEPTED_QUEUED, decision)
    }
}

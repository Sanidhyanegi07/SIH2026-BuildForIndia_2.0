package com.example.georescux.data.relay

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentSeverity
import com.example.georescux.domain.incident.IncidentType
import com.example.georescux.domain.relay.RelayDecision
import com.example.georescux.domain.relay.RelayEngine
import com.example.georescux.domain.relay.RelayTransport
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DurableRelayEngineTest {

    private lateinit var transport: FakeRelayTransport
    private lateinit var store: InMemorySeenEventStore
    private lateinit var relayEngine: RelayEngine
    private lateinit var durableRelayEngine: DurableRelayEngine

    @Before
    fun setUp() {
        transport = FakeRelayTransport(listOf("peerB"))
        store = InMemorySeenEventStore()
        relayEngine = RelayEngine(selfOriginId = "DEVICE_A", transport = transport)
        durableRelayEngine = DurableRelayEngine(relayEngine, store)
    }

    @Test
    fun `accepted local event persists to durable store`() {
        val event = createEvent("e1")
        val decision = durableRelayEngine.onLocalEvent(event)

        assertEquals(RelayDecision.ACCEPTED_FORWARDED, decision)
        assertEquals(setOf("e1"), store.loadAllSeenEventIds())
    }

    @Test
    fun `deduplication survives process restart when seeded from durable store`() {
        val event = createEvent("e1")
        durableRelayEngine.onLocalEvent(event)
        assertEquals(setOf("e1"), store.loadAllSeenEventIds())

        // Simulate app restart / new process lifetime
        val newRelayEngine = RelayEngine(selfOriginId = "DEVICE_A", transport = transport)
        val newDurableRelayEngine = DurableRelayEngine(newRelayEngine, store)

        // Ingesting the same event again after restart must be rejected as DUPLICATE
        val duplicateDecision = newDurableRelayEngine.onLocalEvent(event)
        assertEquals(RelayDecision.DUPLICATE, duplicateDecision)
    }

    private fun createEvent(id: String): IncidentEvent = IncidentEvent(
        eventId = id,
        originId = "DEVICE_A",
        type = IncidentType.SOS,
        occurredAtMs = System.currentTimeMillis(),
        severity = IncidentSeverity.CRITICAL,
    )

    private class FakeRelayTransport(val peers: List<String>) : RelayTransport {
        override fun availablePeers(): List<String> = peers
        override fun send(peerId: String, payload: String): Boolean = true
    }

    private class InMemorySeenEventStore : SeenEventStore {
        private val set = mutableSetOf<String>()
        override fun loadAllSeenEventIds(): Set<String> = set.toSet()
        override fun saveSeenEventId(eventId: String, timestampMs: Long) {
            set.add(eventId)
        }
        override fun clearExpiredBefore(cutoffMs: Long) {}
    }
}

package com.example.georescux.data.relay

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.relay.RelayDecision
import com.example.georescux.domain.relay.RelayEngine
import com.example.georescux.domain.relay.RelayEnvelope

/**
 * Decorator around [RelayEngine] that integrates durable seen-event persistence ([SeenEventStore]).
 * Restores seen event state on startup so deduplication survives process death, and persists
 * accepted events to disk.
 */
class DurableRelayEngine(
    private val delegate: RelayEngine,
    private val store: SeenEventStore,
) {

    init {
        restoreState()
    }

    private fun restoreState() {
        val storedSeenIds = store.loadAllSeenEventIds()
        storedSeenIds.forEach { eventId ->
            // Pre-seed seen events into RelayEngine by ingesting dummy historical records
            val dummyEvent = IncidentEvent(
                eventId = eventId,
                originId = "RESTORED_DURABLE_SEED",
                type = com.example.georescux.domain.incident.IncidentType.ALERT,
                occurredAtMs = System.currentTimeMillis(),
                ttlSeconds = 3600,
            )
            delegate.onEventReceived(RelayEnvelope(dummyEvent, dummyEvent.canonicalString()))
        }
    }

    fun onLocalEvent(event: IncidentEvent, payload: String = event.canonicalString()): RelayDecision {
        val decision = delegate.onLocalEvent(event, payload)
        if (decision == RelayDecision.ACCEPTED_FORWARDED || decision == RelayDecision.ACCEPTED_QUEUED) {
            store.saveSeenEventId(event.eventId)
        }
        return decision
    }

    fun onEventReceived(envelope: RelayEnvelope): RelayDecision {
        val decision = delegate.onEventReceived(envelope)
        if (decision == RelayDecision.ACCEPTED_FORWARDED || decision == RelayDecision.ACCEPTED_QUEUED) {
            store.saveSeenEventId(envelope.event.eventId)
        }
        return decision
    }

    fun onPeerAvailable(peerId: String): Int = delegate.onPeerAvailable(peerId)

    fun pendingCountFor(peerId: String): Int = delegate.pendingCountFor(peerId)
}

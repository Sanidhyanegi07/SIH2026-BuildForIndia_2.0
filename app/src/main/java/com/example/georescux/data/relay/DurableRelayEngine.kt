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
        // FIX Bug 5: call seedSeenEventId() directly instead of routing through onEventReceived
        // with a dummy IncidentEvent.  The old approach ran the ReplayGuard sequence check,
        // which returned STALE_SEQUENCE for any restored event after the first (they all had
        // sequence=0), so those event IDs were never added to seenEventIds and could be
        // re-broadcast to peers after every process restart.
        store.loadAllSeenEventIds().forEach { eventId ->
            delegate.seedSeenEventId(eventId)
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

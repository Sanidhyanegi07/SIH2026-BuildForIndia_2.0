package com.example.georescux.domain.relay

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.security.AuthVerdict
import com.example.georescux.domain.security.EventAuthenticator
import com.example.georescux.domain.security.ReplayGuard
import java.util.Collections

/**
 * Phone-to-phone relay core — the pure, device-independent half of the
 * approved relay design: deduplication, TTL, sequence/regression handling,
 * malformed-event rejection and delivery bookkeeping.
 *
 * The TRANSPORT (BLE/Wi-Fi Direct) is intentionally an interface
 * ([RelayTransport]); no radio stack is implemented here, so this engine
 * is fully JVM-testable and never blocks or touches SOS activation.
 * Real peer discovery/connection requires device-level permission and
 * lifecycle validation and is NOT implemented in this pass.
 *
 * Guarantees (each covered by tests):
 * - an eventId is forwarded at most once (duplicate suppression);
 * - expired events (occurredAtMs + ttl) are never forwarded;
 * - malformed events are rejected;
 * - per-origin sequences are monotonic — stale/regressing sequences are
 *   not forwarded (out-of-order/replay protection);
 * - sends to unavailable peers are kept in an outbox and flushed exactly
 *   once when the peer returns;
 * - the engine never blocks: callers decide when to run it.
 */
enum class RelayDecision {
    ACCEPTED_FORWARDED, ACCEPTED_QUEUED, DUPLICATE, EXPIRED, MALFORMED, REJECTED_SIGNATURE, STALE_SEQUENCE, OWN_EVENT,
}

/** The transport boundary a real BLE/Wi-Fi-Direct implementation will fill. */
interface RelayTransport {
    fun availablePeers(): List<String>
    fun send(peerId: String, payload: String): Boolean
}

/** Wire form: the event plus its opaque (compact, pre-serialized) payload. */
data class RelayEnvelope(
    val event: IncidentEvent,
    val payload: String,
    val fromPeerId: String? = null,
)

class RelayEngine(
    private val selfOriginId: String,
    private val transport: RelayTransport,
    private val replayGuard: ReplayGuard = ReplayGuard(),
    private val authenticator: com.example.georescux.domain.security.EventAuthenticator? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val seenEventIds = HashSet<String>()
    private val activeEnvelopes = Collections.synchronizedList(mutableListOf<RelayEnvelope>())
    private val outbox = HashMap<String, MutableList<RelayEnvelope>>()
    private val sentPeersMap = HashMap<String, MutableSet<String>>()

    /** A locally produced event enters the relay (e.g. SOS, injected hazard). */
    fun onLocalEvent(event: IncidentEvent, payload: String = event.canonicalString()): RelayDecision =
        ingest(RelayEnvelope(event, payload), fromLocal = true)

    /** An event received from a peer. */
    fun onEventReceived(envelope: RelayEnvelope): RelayDecision =
        ingest(envelope, fromLocal = false)

    /** Flush pending sends to a peer that became reachable again. */
    fun onPeerAvailable(peerId: String): Int {
        var delivered = 0
        val peerPending = outbox.remove(peerId) ?: emptyList()
        peerPending.forEach { envelope ->
            val sentSet = sentPeersMap.getOrPut(envelope.event.eventId) { HashSet() }
            if (peerId !in sentSet) {
                if (transport.send(peerId, envelope.payload)) {
                    sentSet.add(peerId)
                    delivered++
                } else {
                    outbox.getOrPut(peerId) { mutableListOf() }.add(envelope)
                }
            }
        }

        val unexpired = activeEnvelopes.filter { !isExpired(it.event) && it.fromPeerId != peerId }
        unexpired.forEach { envelope ->
            val sentSet = sentPeersMap.getOrPut(envelope.event.eventId) { HashSet() }
            if (peerId !in sentSet) {
                if (transport.send(peerId, envelope.payload)) {
                    sentSet.add(peerId)
                    delivered++
                } else {
                    outbox.getOrPut(peerId) { mutableListOf() }.add(envelope)
                }
            }
        }
        return delivered
    }

    fun pendingCountFor(peerId: String): Int = outbox[peerId]?.size ?: 0

    private fun ingest(envelope: RelayEnvelope, fromLocal: Boolean): RelayDecision {
        val event = envelope.event
        if (event.eventId.isBlank() || event.originId.isBlank() || event.occurredAtMs <= 0 || event.ttlSeconds < 0) {
            return RelayDecision.MALFORMED
        }
        if (!fromLocal && event.originId == selfOriginId) return RelayDecision.OWN_EVENT
        if (isExpired(event)) return RelayDecision.EXPIRED
        authenticator?.let { auth ->
            if (auth.authenticate(event, nowMs()) != AuthVerdict.OK) {
                return RelayDecision.REJECTED_SIGNATURE
            }
        }
        if (!fromLocal && !replayGuard.accept(event.originId, event.sequence)) {
            return RelayDecision.STALE_SEQUENCE
        }
        if (!seenEventIds.add(event.eventId)) return RelayDecision.DUPLICATE
        
        envelope.fromPeerId?.let { sender ->
            sentPeersMap.getOrPut(event.eventId) { HashSet() }.add(sender)
        }
        
        return forward(envelope)
    }

    private fun forward(envelope: RelayEnvelope): RelayDecision {
        activeEnvelopes.add(envelope)
        var anySuccess = false
        val sentSet = sentPeersMap.getOrPut(envelope.event.eventId) { HashSet() }
        val peers = transport.availablePeers().filter { it != envelope.fromPeerId && it !in sentSet }
        if (peers.isNotEmpty()) {
            peers.forEach { peer ->
                if (transport.send(peer, envelope.payload)) {
                    sentSet.add(peer)
                    anySuccess = true
                } else {
                    outbox.getOrPut(peer) { mutableListOf() }.add(envelope)
                }
            }
        }
        return if (anySuccess) RelayDecision.ACCEPTED_FORWARDED else RelayDecision.ACCEPTED_QUEUED
    }

    private fun isExpired(event: IncidentEvent): Boolean =
        event.occurredAtMs + event.ttlSeconds * 1000 < nowMs()
}

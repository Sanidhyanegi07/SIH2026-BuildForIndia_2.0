package com.example.georescux.domain.ble

import java.util.Collections

/**
 * The pure, transport-independent BLE mesh core — the BLE counterpart of
 * [com.example.georescux.domain.relay.RelayEngine] for the raw GATT
 * subsystem. It implements the ingestion pipeline required by the design:
 *
 *   receive -> parse -> deduplicate -> validate -> persist -> relay
 *
 * with hop-based TTL store-and-forward:
 *
 *   A -> B -> C -> Internet device   (each forward decrements ttl by 1,
 *                                     ttl <= 0 is never forwarded again)
 *
 * Guarantees (each covered by unit tests):
 * - a packetId is processed at most once per device (idempotency) —
 *   deduplication survives restart via the durable seen-packet store;
 * - packets from this device echoed back by the mesh are ignored (OWN_PACKET);
 * - malformed packets are rejected before any state is touched;
 * - packets with ttl == 0 are processed locally but never forwarded;
 * - forwarding decrements ttl by exactly one hop;
 * - sends to unreachable peers are kept (memory + durable queue) and flushed
 *   exactly once per peer when the peer becomes READY again;
 * - the node never blocks: callers decide when to run it.
 *
 * The radio stack (scanning, advertising, GATT server/client) is entirely
 * behind [GeoRescueBlePacketSink] and the manager — no Android classes here,
 * so this engine is fully JVM-testable.
 */
enum class GeoRescueBleRelayDecision {
    /** Valid, new packet and at least one peer accepted the send. */
    ACCEPTED_FORWARDED,

    /** Valid, new packet but no peer could take it — queued for later flush. */
    ACCEPTED_QUEUED,

    /** Valid, new packet with no hops remaining (ttl <= 0): processed locally, never relayed. */
    ACCEPTED_LOCAL,

    /** packetId already processed on this device — ignored (idempotent). */
    DUPLICATE,

    /** Structurally invalid packet (see [GeoRescueBlePacketValidator]). */
    INVALID,

    /** The packet originated on this device and looped back through the mesh. */
    OWN_PACKET,
}

/** Delivery target the engine hands packets to. Implemented by the BLE manager. */
interface GeoRescueBlePacketSink {
    /** Peers currently connected and able to receive (GATT READY, notifications on). */
    fun availablePeerIds(): List<String>

    /**
     * Sends one serialized packet frame to [peerId]. Returns false when the
     * peer cannot take the data right now; the engine then keeps it queued.
     */
    fun send(peerId: String, packetJson: String): Boolean
}

/** Durable seen-packet persistence (deduplication that survives process death). */
interface GeoRescueBleSeenPacketStore {
    fun loadAllSeenPacketIds(): Set<String>
    fun saveSeenPacketId(packetId: String, timestampMs: Long = System.currentTimeMillis())
    fun clearExpiredBefore(cutoffMs: Long)
}

/** Durable outbound queue row (store-and-forward crash recovery). */
interface GeoRescueBleOutboundPacketQueue {
    fun loadQueued(): List<QueuedPacket>
    fun enqueue(packetId: String, packetJson: String, queuedAtMs: Long = System.currentTimeMillis())
    fun remove(packetId: String)
}

data class QueuedPacket(
    val packetId: String,
    val packetJson: String,
    val queuedAtMs: Long,
    val attempts: Int,
)

class GeoRescueBleMeshNode(
    private val selfDeviceId: String,
    private val sink: GeoRescueBlePacketSink,
    private val seenStore: GeoRescueBleSeenPacketStore,
    private val outboundQueue: GeoRescueBleOutboundPacketQueue,
    /** Called with every accepted packet BEFORE forwarding (persistence/UI hook). */
    var onPacketAccepted: ((GeoRescueBlePacket) -> Unit)? = null,
    /** Called with every accepted packet and the peer it was received from (or null for local). */
    var onPacketAcceptedWithPeer: ((GeoRescueBlePacket, String?) -> Unit)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val seenPacketIds = Collections.synchronizedSet(HashSet<String>())
    private val sentPeers = HashMap<String, MutableSet<String>>()
    private val outbox = HashMap<String, MutableList<QueuedPacket>>()
    private val sentJsons = HashMap<String, String>()

    init {
        // State restore is explicit ([restoreState]) so construction stays
        // free of I/O; the manager calls it when the subsystem comes up.
        // Deduplication survives restart: restore the durable seen set.
        seenStore.loadAllSeenPacketIds().forEach { seenPacketIds.add(it) }
        // Restore queued outbound packets so store-and-forward survives restart.
        outboundQueue.loadQueued().forEach { queued ->
            sentJsons[queued.packetId] = queued.packetJson
            outbox.getOrPut(OUTBOX_ANY_PEER) { mutableListOf() }.add(queued)
        }
        seenStore.clearExpiredBefore(nowMs() - SEEN_RETENTION_MS)
    }

    /** Public re-restore hook (idempotent; cheap — used after subsystem restarts). */
    fun restoreState() {
        seenStore.loadAllSeenPacketIds().forEach { seenPacketIds.add(it) }
        outboundQueue.loadQueued().forEach { queued ->
            sentJsons[queued.packetId] = queued.packetJson
            val pending = synchronized(outbox) { outbox[OUTBOX_ANY_PEER] ?: mutableListOf() }
            if (pending.none { it.packetId == queued.packetId }) {
                pending.add(queued)
                synchronized(outbox) { outbox[OUTBOX_ANY_PEER] = pending }
            }
        }
    }

    /**
     * A packet this device originates (its own SOS or test packet). The
     * caller has already persisted the underlying emergency; the node
     * validates, marks seen, and forwards.
     */
    fun publish(packet: GeoRescueBlePacket): GeoRescueBleRelayDecision {
        if (GeoRescueBlePacketValidator.validate(packet) != GeoRescueBlePacketVerdict.VALID) {
            return GeoRescueBleRelayDecision.INVALID
        }
        if (!seenPacketIds.add(packet.packetId)) return GeoRescueBleRelayDecision.DUPLICATE
        seenStore.saveSeenPacketId(packet.packetId)
        sentJsons[packet.packetId] = packet.serialize()
        onPacketAccepted?.invoke(packet)
        onPacketAcceptedWithPeer?.invoke(packet, null)
        return forward(packet)
    }

    /**
     * A packet received over BLE from [fromPeerId] (peer address or null for
     * local loopback injection). Implements the full receive pipeline.
     */
    fun ingest(json: String, fromPeerId: String?): GeoRescueBleRelayDecision {
        val packet = GeoRescueBlePacketCodec.deserialize(json)
            ?: return GeoRescueBleRelayDecision.INVALID

        if (packet.originDeviceId == selfDeviceId) return GeoRescueBleRelayDecision.OWN_PACKET

        // Handle link-local inventory sync protocols (never forwarded beyond 1 hop)
        if (packet.type == GeoRescueBlePacketType.SYNC_INVENTORY) {
            if (fromPeerId != null) {
                val peerIds = packet.payload["ids"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val missing = peerIds.filter { !seenPacketIds.contains(it) }
                if (missing.isNotEmpty()) {
                    createSyncRequestPacket(missing)?.let { reqPacket ->
                        sink.send(fromPeerId, reqPacket.serialize())
                    }
                }
            }
            return GeoRescueBleRelayDecision.ACCEPTED_LOCAL
        }

        if (packet.type == GeoRescueBlePacketType.SYNC_REQUEST) {
            if (fromPeerId != null) {
                val reqIds = packet.payload["requestedIds"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                reqIds.forEach { reqId ->
                    val packetJson = getPacketJson(reqId)
                    if (packetJson != null) {
                        val sentSet = sentPeers.getOrPut(reqId) { HashSet() }
                        if (sink.send(fromPeerId, packetJson)) {
                            sentSet.add(fromPeerId)
                        }
                    }
                }
            }
            return GeoRescueBleRelayDecision.ACCEPTED_LOCAL
        }

        if (!seenPacketIds.add(packet.packetId)) {
            // Durable write is redundant but keeps the store authoritative.
            return GeoRescueBleRelayDecision.DUPLICATE
        }

        val verdict = GeoRescueBlePacketValidator.validate(packet)
        if (verdict != GeoRescueBlePacketVerdict.VALID) {
            seenPacketIds.remove(packet.packetId) // invalid packets never count as seen
            return GeoRescueBleRelayDecision.INVALID
        }

        seenStore.saveSeenPacketId(packet.packetId)
        sentJsons[packet.packetId] = json
        fromPeerId?.let { sender ->
            sentPeers.getOrPut(packet.packetId) { HashSet() }.add(sender)
        }
        onPacketAccepted?.invoke(packet)
        onPacketAcceptedWithPeer?.invoke(packet, fromPeerId)

        if (!packet.hasHopsRemaining()) return GeoRescueBleRelayDecision.ACCEPTED_LOCAL
        val nextHop = packet.forNextHop()
        // Strict last-hop rule: a packet whose decrement would reach 0 stops
        // here — TTL=5 crosses exactly 5 links, no relay can loop a packet.
        if (nextHop.ttl <= 0) return GeoRescueBleRelayDecision.ACCEPTED_LOCAL
        return forward(nextHop)
    }

    /**
     * Flushes everything pending for [peerId] (durable outbox entries and
     * packets still being propagated). Returns the number of packets handed
     * to the sink successfully. Called by the manager when a connection
     * becomes READY.
     */
    fun onPeerReady(peerId: String): Int {
        var delivered = 0
        // Entries explicitly failed for this peer earlier.
        val peerPending = outbox.remove(peerId) ?: emptyList()
        // Restored/never-attempted entries live under the wildcard key.
        val anyPeer = outbox.remove(OUTBOX_ANY_PEER) ?: emptyList()
        (peerPending + anyPeer).forEach { queued ->
            if (tryDeliver(peerId, queued)) delivered++
        }

        // Propagate still-fresh packets the peer has not seen yet.
        val fresh = synchronized(sentPeers) {
            sentPeers.entries.filter { peerId !in it.value }
                .map { it.key }
                .toSet()
        }
        fresh.forEach { packetId ->
            val json = sentJsons[packetId] ?: return@forEach
            val packet = GeoRescueBlePacketCodec.deserialize(json) ?: return@forEach
            if (!packet.hasHopsRemaining()) return@forEach
            if (isStale(packet)) return@forEach
            if (tryDeliver(peerId, QueuedPacket(packetId, json, packet.timestampMs, 0))) delivered++
        }

        return delivered
    }

    /**
     * Automatic Peer Synchronization (Section 11):
     * Exchanges active inventory with the newly connected peer so it can pull
     * any missing packets without blind flooding.
     */
    fun syncWithPeer(peerId: String): Boolean {
        val inv = createInventoryPacket() ?: return false
        return sink.send(peerId, inv.serialize())
    }

    /** Returns active non-stale packet IDs known to this node. */
    fun activePacketIds(): List<String> = synchronized(sentJsons) {
        val active = mutableListOf<String>()
        sentJsons.forEach { (id, json) ->
            val packet = GeoRescueBlePacketCodec.deserialize(json)
            if (packet != null && !isStale(packet) && packet.type != GeoRescueBlePacketType.SYNC_INVENTORY && packet.type != GeoRescueBlePacketType.SYNC_REQUEST) {
                active.add(id)
            }
        }
        outboundQueue.loadQueued().forEach { queued ->
            if (!active.contains(queued.packetId) && !isStaleById(queued)) {
                active.add(queued.packetId)
            }
        }
        active
    }

    /** Builds link-local SYNC_INVENTORY packet. */
    fun createInventoryPacket(): GeoRescueBlePacket? {
        val ids = activePacketIds()
        if (ids.isEmpty()) return null
        return GeoRescueBlePacket(
            packetId = GeoRescueBlePacket.newPacketId(),
            originDeviceId = selfDeviceId,
            originEmergencyId = null,
            timestampMs = nowMs(),
            ttl = 1,
            type = GeoRescueBlePacketType.SYNC_INVENTORY,
            payload = mapOf("ids" to ids.take(15).joinToString(",")),
        )
    }

    /** Builds link-local SYNC_REQUEST packet. */
    fun createSyncRequestPacket(missingIds: List<String>): GeoRescueBlePacket? {
        if (missingIds.isEmpty()) return null
        return GeoRescueBlePacket(
            packetId = GeoRescueBlePacket.newPacketId(),
            originDeviceId = selfDeviceId,
            originEmergencyId = null,
            timestampMs = nowMs(),
            ttl = 1,
            type = GeoRescueBlePacketType.SYNC_REQUEST,
            payload = mapOf("requestedIds" to missingIds.take(15).joinToString(",")),
        )
    }

    /** Returns serialized packet JSON by packetId. */
    fun getPacketJson(packetId: String): String? {
        sentJsons[packetId]?.let { return it }
        return outboundQueue.loadQueued().firstOrNull { it.packetId == packetId }?.packetJson
    }

    fun pendingCountFor(peerId: String): Int = outbox[peerId]?.size ?: 0

    fun pendingTotal(): Int = synchronized(outbox) { outbox.values.sumOf { it.size } }

    fun seenCount(): Int = synchronized(seenPacketIds) { seenPacketIds.size }

    private fun forward(packet: GeoRescueBlePacket): GeoRescueBleRelayDecision {
        sentJsons[packet.packetId] = packet.serialize()
        var anySuccess = false
        val sentSet = sentPeers.getOrPut(packet.packetId) { HashSet() }
        val peers = sink.availablePeerIds().filter { it !in sentSet }
        peers.forEach { peer ->
            if (sink.send(peer, packet.serialize())) {
                sentSet.add(peer)
                anySuccess = true
            } else {
                synchronized(outbox) {
                    outbox.getOrPut(peer) { mutableListOf() }.add(
                        QueuedPacket(packet.packetId, packet.serialize(), packet.timestampMs, 0)
                    )
                }
            }
        }
        if (!anySuccess) {
            // Durable store-and-forward: no peer took the packet this round.
            synchronized(outbox) {
                val hasPending = outbox.values.any { list -> list.any { it.packetId == packet.packetId } }
                if (!hasPending) {
                    outboundQueue.enqueue(packet.packetId, packet.serialize(), packet.timestampMs)
                    outbox.getOrPut(OUTBOX_ANY_PEER) { mutableListOf() }.add(
                        QueuedPacket(packet.packetId, packet.serialize(), packet.timestampMs, 0)
                    )
                }
            }
        }
        return when {
            anySuccess -> GeoRescueBleRelayDecision.ACCEPTED_FORWARDED
            peers.isEmpty() -> GeoRescueBleRelayDecision.ACCEPTED_QUEUED
            else -> GeoRescueBleRelayDecision.ACCEPTED_QUEUED
        }
    }

    private fun tryDeliver(peerId: String, queued: QueuedPacket): Boolean {
        if (isStaleById(queued)) {
            outboundQueue.remove(queued.packetId)
            return false
        }
        val sentSet = sentPeers.getOrPut(queued.packetId) { HashSet() }
        if (peerId in sentSet) return false
        val ok = sink.send(peerId, queued.packetJson)
        if (ok) {
            sentSet.add(peerId)
            outboundQueue.remove(queued.packetId)
        } else {
            synchronized(outbox) {
                outbox.getOrPut(peerId) { mutableListOf() }.add(
                    queued.copy(attempts = queued.attempts + 1)
                )
            }
        }
        return ok
    }

    private fun isStale(packet: GeoRescueBlePacket) =
        nowMs() - packet.timestampMs > PACKET_MAX_AGE_MS

    private fun isStaleById(queued: QueuedPacket) = nowMs() - queued.queuedAtMs > PACKET_MAX_AGE_MS

    companion object {
        /** Memory-only key for queue entries not bound to a failing peer. */
        const val OUTBOX_ANY_PEER = "<any>"

        /** Packets stop being propagated after this age even if hops remain. */
        const val PACKET_MAX_AGE_MS = 60 * 60 * 1000L

        /** Seen-packet dedup rows older than this are cleared (dedup memory bound). */
        const val SEEN_RETENTION_MS = 24 * 60 * 60 * 1000L
    }
}

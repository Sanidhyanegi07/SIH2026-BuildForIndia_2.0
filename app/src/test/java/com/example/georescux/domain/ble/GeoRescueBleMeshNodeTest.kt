package com.example.georescux.domain.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the pure mesh core: duplicate detection, TTL decrement,
 * TTL expiration, packet validation, relay decisions and
 * store-and-forward queueing.
 */
class GeoRescueBleMeshNodeTest {

    private class FakeSeenStore : GeoRescueBleSeenPacketStore {
        val seen = HashMap<String, Long>()

        override fun loadAllSeenPacketIds(): Set<String> = seen.keys.toSet()

        override fun saveSeenPacketId(packetId: String, timestampMs: Long) {
            seen[packetId] = timestampMs
        }

        override fun clearExpiredBefore(cutoffMs: Long) {
            seen.entries.removeIf { it.value < cutoffMs }
        }
    }

    private class FakeOutboundQueue : GeoRescueBleOutboundPacketQueue {
        val rows = LinkedHashMap<String, QueuedPacket>()

        override fun loadQueued(): List<QueuedPacket> = rows.values.toList()

        override fun enqueue(packetId: String, packetJson: String, queuedAtMs: Long) {
            rows[packetId] = QueuedPacket(packetId, packetJson, queuedAtMs, 0)
        }

        override fun remove(packetId: String) {
            rows.remove(packetId)
        }
    }

    /** Deterministic sink: deliverable peers can be changed per test. */
    private class FakeSink(
        var reachable: MutableSet<String> = mutableSetOf(),
        var failPeers: MutableSet<String> = mutableSetOf(),
    ) : GeoRescueBlePacketSink {
        val sent = mutableListOf<Pair<String, String>>() // (peerId, json)

        override fun availablePeerIds(): List<String> = reachable.filter { it !in failPeers }

        override fun send(peerId: String, packetJson: String): Boolean {
            sent.add(peerId to packetJson)
            return peerId !in failPeers
        }
    }

    private lateinit var seenStore: FakeSeenStore
    private lateinit var outboundQueue: FakeOutboundQueue
    private lateinit var sink: FakeSink
    private var nowMs = 1_000_000L
    private val accepted = mutableListOf<GeoRescueBlePacket>()

    private fun buildNode(selfDeviceId: String = "DEVICE_B"): GeoRescueBleMeshNode =
        GeoRescueBleMeshNode(
            selfDeviceId = selfDeviceId,
            sink = sink,
            seenStore = seenStore,
            outboundQueue = outboundQueue,
            onPacketAccepted = { packet -> accepted.add(packet) },
            nowMs = { nowMs },
        )

    @Before
    fun setUp() {
        seenStore = FakeSeenStore()
        outboundQueue = FakeOutboundQueue()
        sink = FakeSink()
        accepted.clear()
    }

    private fun sosPacket(
        packetId: String = "GRX-${Math.random()}",
        origin: String = "DEVICE_A",
        ttl: Int = 5,
        timestampMs: Long = 1_000_000L - 60_000, // 60s ago: fresh
    ): GeoRescueBlePacket = GeoRescueBlePacket(
        packetId = packetId,
        originDeviceId = origin,
        originEmergencyId = "SOS-1",
        timestampMs = timestampMs,
        ttl = ttl,
        type = GeoRescueBlePacketType.SOS,
        latitude = 30.0,
        longitude = 78.0,
        status = "ACTIVE",
    )

    // --- Duplicate detection ---

    @Test
    fun `same packetId received twice is processed once (DUPLICATE)`() {
        val node = buildNode()
        val packet = sosPacket(packetId = "GRX-dup")
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_QUEUED, node.ingest(packet.serialize(), null))
        assertEquals(GeoRescueBleRelayDecision.DUPLICATE, node.ingest(packet.serialize(), null))
        assertEquals(1, accepted.size)
    }

    @Test
    fun `duplicate detection survives node rebuild from the same store (process restart)`() {
        val packet = sosPacket(packetId = "GRX-restart")
        buildNode().ingest(packet.serialize(), null)
        // New node over the SAME store = process death + restore.
        val second = buildNode()
        assertEquals(GeoRescueBleRelayDecision.DUPLICATE, second.ingest(packet.serialize(), null))
    }

    // --- Validation gate ---

    @Test
    fun `malformed json is INVALID and never marked seen`() {
        val node = buildNode()
        assertEquals(GeoRescueBleRelayDecision.INVALID, node.ingest("total garbage", null))
        assertEquals(GeoRescueBleRelayDecision.INVALID, node.ingest("""{"foo":1}""", null))
    }

    @Test
    fun `structurally invalid packet is INVALID and does not pollute the seen set`() {
        val node = buildNode()
        val bad = sosPacket(packetId = "GRX-bad").copy(ttl = -3)
        assertEquals(GeoRescueBleRelayDecision.INVALID, node.ingest(bad.serialize(), null))
        // A repaired packet with the same id must still be accepted.
        val good = bad.copy(ttl = 5)
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_QUEUED, node.ingest(good.serialize(), null))
    }

    @Test
    fun `own-device loopback packets are ignored (OWN_PACKET)`() {
        val node = buildNode(selfDeviceId = "DEVICE_A")
        val packet = sosPacket(origin = "DEVICE_A")
        assertEquals(GeoRescueBleRelayDecision.OWN_PACKET, node.ingest(packet.serialize(), null))
        assertTrue(accepted.isEmpty())
    }

    // --- TTL / relay decisions ---

    @Test
    fun `ingested packet is forwarded with ttl decremented by exactly one`() {
        sink.reachable.add("PEER_1")
        val node = buildNode()
        val decision = node.ingest(sosPacket(packetId = "GRX-ttl", ttl = 5).serialize(), null)
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_FORWARDED, decision)
        assertEquals(1, sink.sent.size)
        val forwarded = GeoRescueBlePacketCodec.deserialize(sink.sent.first().second)!!
        assertEquals(4, forwarded.ttl)
    }

    @Test
    fun `packet with ttl 1 is processed but NOT forwarded (TTL expiration)`() {
        sink.reachable.add("PEER_1")
        val node = buildNode()
        val decision = node.ingest(sosPacket(packetId = "GRX-ttl1", ttl = 1).serialize(), null)
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_LOCAL, decision)
        assertTrue(sink.sent.isEmpty())
        assertEquals(1, accepted.size) // still processed locally
    }

    @Test
    fun `packet with ttl 0 is processed but never relayed`() {
        sink.reachable.add("PEER_1")
        val node = buildNode()
        val decision = node.ingest(sosPacket(packetId = "GRX-ttl0", ttl = 0).serialize(), null)
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_LOCAL, decision)
        assertTrue(sink.sent.isEmpty())
    }

    @Test
    fun `relayed packet is never bounced back to the sender`() {
        sink.reachable.add("PEER_1")
        val node = buildNode()
        // fromPeerId = PEER_1: the only reachable peer is the sender itself.
        val decision = node.ingest(sosPacket(packetId = "GRX-bounce").serialize(), fromPeerId = "PEER_1")
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_QUEUED, decision)
        assertTrue(sink.sent.isEmpty())
    }

    // --- Store-and-forward ---

    @Test
    fun `publish with no reachable peer queues the packet durably`() {
        val node = buildNode()
        val decision = node.publish(sosPacket(packetId = "GRX-queue", origin = "DEVICE_B"))
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_QUEUED, decision)
        assertTrue(outboundQueue.rows.containsKey("GRX-queue"))
        assertEquals(1, node.pendingTotal())
    }

    @Test
    fun `queued packet is flushed when the peer becomes ready and then removed from the queue`() {
        val node = buildNode(selfDeviceId = "DEVICE_B")
        node.publish(sosPacket(packetId = "GRX-flush", origin = "DEVICE_B"))
        assertEquals(1, outboundQueue.rows.size)

        sink.reachable.add("PEER_1")
        val delivered = node.onPeerReady("PEER_1")
        assertEquals(1, delivered)
        assertTrue(outboundQueue.rows.isEmpty())
        assertEquals(1, sink.sent.size) // sent exactly once
        assertEquals("PEER_1", sink.sent.first().first)
    }

    @Test
    fun `failed flush keeps the packet queued for the failing peer`() {
        val node = buildNode(selfDeviceId = "DEVICE_B")
        node.publish(sosPacket(packetId = "GRX-fail", origin = "DEVICE_B"))

        sink.reachable.add("PEER_1")
        sink.failPeers.add("PEER_1")
        node.onPeerReady("PEER_1")
        assertTrue(sink.sent.isNotEmpty())
        assertTrue(outboundQueue.rows.containsKey("GRX-fail"))

        // Peer heals:
        sink.failPeers.clear()
        val delivered = node.onPeerReady("PEER_1")
        assertEquals(1, delivered)
        assertTrue(outboundQueue.rows.isEmpty())
    }

    @Test
    fun `stale packets are not propagated even when hops remain`() {
        val node = buildNode(selfDeviceId = "DEVICE_B")
        // Published 2 hours ago.
        node.publish(sosPacket(packetId = "GRX-stale", origin = "DEVICE_B", timestampMs = nowMs - 2 * 60 * 60 * 1000))
        sink.reachable.add("PEER_1")
        val delivered = node.onPeerReady("PEER_1")
        assertEquals(0, delivered)
    }

    @Test
    fun `seen packets older than the retention window are cleared`() {
        nowMs = 90_000_000_000L // far in the future so the retention cutoff is positive
        seenStore.saveSeenPacketId("GRX-old", timestampMs = 1L)
        seenStore.saveSeenPacketId("GRX-new", timestampMs = nowMs - 1000)
        buildNode()
        // init cleared anything older than 24h before nowMs.
        assertFalse(seenStore.seen.containsKey("GRX-old"))
        assertTrue(seenStore.seen.containsKey("GRX-new"))
    }

    @Test
    fun `publish is idempotent - republishing the same packetId returns DUPLICATE`() {
        val node = buildNode(selfDeviceId = "DEVICE_B")
        val packet = sosPacket(packetId = "GRX-idem", origin = "DEVICE_B")
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_QUEUED, node.publish(packet))
        assertEquals(GeoRescueBleRelayDecision.DUPLICATE, node.publish(packet))
    }

    @Test
    fun `publish rejects invalid packets without queueing`() {
        val node = buildNode(selfDeviceId = "DEVICE_B")
        val decision = node.publish(sosPacket(packetId = "GRX-badpub").copy(ttl = 999))
        assertEquals(GeoRescueBleRelayDecision.INVALID, decision)
        assertTrue(outboundQueue.rows.isEmpty())
    }

    // --- Multi-hop loop guard (A -> B -> C without re-forward loops) ---

    @Test
    fun `two different packets from one origin are both processed`() {
        sink.reachable.add("PEER_1")
        val node = buildNode()
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_FORWARDED, node.ingest(sosPacket(packetId = "GRX-1").serialize(), null))
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_FORWARDED, node.ingest(sosPacket(packetId = "GRX-2").serialize(), null))
        assertEquals(2, sink.sent.size)
    }

    @Test
    fun `onPacketAcceptedWithPeer delivers fromPeerId on ingest and null on publish`() {
        var deliveredPeer: String? = "UNSET"
        val node = GeoRescueBleMeshNode(
            selfDeviceId = "DEVICE_B",
            sink = sink,
            seenStore = seenStore,
            outboundQueue = outboundQueue,
            onPacketAcceptedWithPeer = { _, peerId -> deliveredPeer = peerId },
            nowMs = { nowMs },
        )

        val peerPacket = sosPacket(packetId = "GRX-from-peer", origin = "DEVICE_A")
        node.ingest(peerPacket.serialize(), "PEER_MAC_ADDRESS_1")
        assertEquals("PEER_MAC_ADDRESS_1", deliveredPeer)

        val localPacket = sosPacket(packetId = "GRX-local-pub", origin = "DEVICE_B")
        node.publish(localPacket)
        assertNull(deliveredPeer)
    }
}


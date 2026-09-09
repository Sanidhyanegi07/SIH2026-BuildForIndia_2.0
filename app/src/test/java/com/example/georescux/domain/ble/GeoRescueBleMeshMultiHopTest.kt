package com.example.georescux.domain.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end multi-hop unit tests for the GeoRescueX BLE mesh architecture:
 *
 * Scenarios tested:
 * 1. Multi-hop propagation: Phone A -> Phone B -> Phone C -> Phone D
 * 2. TTL decrement and terminal hop: TTL=5 -> 4 -> 3 -> 2 -> ... -> 0 (stops forwarding)
 * 3. Duplicate rejection and loop suppression (A -> B -> A and multi-path B -> C -> B)
 * 4. Automatic peer inventory synchronization (Section 11: SYNC_INVENTORY and SYNC_REQUEST)
 * 5. Branched mesh topology: A -> B -> (C & D)
 */
class GeoRescueBleMeshMultiHopTest {

    private class TestNode(
        val deviceId: String,
        val seenStore: FakeSeenStore = FakeSeenStore(),
        val outboundQueue: FakeOutboundQueue = FakeOutboundQueue(),
    ) {
        val reachablePeers = mutableSetOf<String>()
        val receivedPackets = mutableListOf<GeoRescueBlePacket>()
        val transmitted = mutableListOf<Pair<String, String>>() // (targetPeer, packetJson)

        private val sink = object : GeoRescueBlePacketSink {
            override fun availablePeerIds(): List<String> = reachablePeers.toList()

            override fun send(peerId: String, packetJson: String): Boolean {
                transmitted.add(peerId to packetJson)
                return true
            }
        }

        val meshNode: GeoRescueBleMeshNode = GeoRescueBleMeshNode(
            selfDeviceId = deviceId,
            sink = sink,
            seenStore = seenStore,
            outboundQueue = outboundQueue,
            onPacketAccepted = { receivedPackets.add(it) },
            nowMs = { 1_000_000L },
        )
    }

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

    private fun createSosPacket(
        packetId: String = "GRX-SOS-001",
        origin: String = "PHONE_A",
        ttl: Int = 5,
        lat: Double = 28.6139,
        lng: Double = 77.2090,
    ): GeoRescueBlePacket = GeoRescueBlePacket(
        packetId = packetId,
        originDeviceId = origin,
        originEmergencyId = "EMG-001",
        timestampMs = 1_000_000L,
        ttl = ttl,
        type = GeoRescueBlePacketType.SOS,
        latitude = lat,
        longitude = lng,
        status = "ACTIVE",
    )

    @Test
    fun testMultiHopPropagation_A_to_B_to_C_to_D() {
        val nodeA = TestNode("PHONE_A")
        val nodeB = TestNode("PHONE_B")
        val nodeC = TestNode("PHONE_C")
        val nodeD = TestNode("PHONE_D")

        // Topology: A -> B -> C -> D
        nodeA.reachablePeers.add("PHONE_B")
        nodeB.reachablePeers.add("PHONE_C")
        nodeC.reachablePeers.add("PHONE_D")

        // Step 1: Phone A publishes original SOS with TTL = 5
        val initialPacket = createSosPacket(packetId = "GRX-SOS-100", origin = "PHONE_A", ttl = 5)
        val decisionA = nodeA.meshNode.publish(initialPacket)
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_FORWARDED, decisionA)
        assertEquals(1, nodeA.transmitted.size)
        assertEquals("PHONE_B", nodeA.transmitted[0].first)

        // Step 2: Phone B ingests frame from Phone A
        val wireFromA = nodeA.transmitted[0].second
        val decisionB = nodeB.meshNode.ingest(wireFromA, fromPeerId = "PHONE_A")
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_FORWARDED, decisionB)
        assertEquals(1, nodeB.receivedPackets.size)
        assertEquals(5, nodeB.receivedPackets[0].ttl)
        // Check forwarding to C with decremented TTL = 4
        assertEquals(1, nodeB.transmitted.size)
        assertEquals("PHONE_C", nodeB.transmitted[0].first)
        val wireFromB = nodeB.transmitted[0].second
        val parsedFromB = GeoRescueBlePacketCodec.deserialize(wireFromB)
        assertNotNull(parsedFromB)
        assertEquals(4, parsedFromB!!.ttl)

        // Step 3: Phone C ingests frame from Phone B
        val decisionC = nodeC.meshNode.ingest(wireFromB, fromPeerId = "PHONE_B")
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_FORWARDED, decisionC)
        assertEquals(1, nodeC.receivedPackets.size)
        assertEquals(4, nodeC.receivedPackets[0].ttl)
        // Check forwarding to D with decremented TTL = 3
        assertEquals(1, nodeC.transmitted.size)
        assertEquals("PHONE_D", nodeC.transmitted[0].first)
        val wireFromC = nodeC.transmitted[0].second
        val parsedFromC = GeoRescueBlePacketCodec.deserialize(wireFromC)
        assertNotNull(parsedFromC)
        assertEquals(3, parsedFromC!!.ttl)

        // Step 4: Phone D (Internet-capable node) ingests frame from Phone C
        val decisionD = nodeD.meshNode.ingest(wireFromC, fromPeerId = "PHONE_C")
        // D has no reachable peers, so it accepts and queues
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_QUEUED, decisionD)
        assertEquals(1, nodeD.receivedPackets.size)
        assertEquals(3, nodeD.receivedPackets[0].ttl)
        assertEquals("PHONE_A", nodeD.receivedPackets[0].originDeviceId)
        assertEquals(28.6139, nodeD.receivedPackets[0].latitude!!, 0.0001)
        assertEquals(77.2090, nodeD.receivedPackets[0].longitude!!, 0.0001)

        // Verify all 4 nodes stored the packet in durable seen store
        assertTrue(nodeA.seenStore.seen.containsKey("GRX-SOS-100"))
        assertTrue(nodeB.seenStore.seen.containsKey("GRX-SOS-100"))
        assertTrue(nodeC.seenStore.seen.containsKey("GRX-SOS-100"))
        assertTrue(nodeD.seenStore.seen.containsKey("GRX-SOS-100"))
    }

    @Test
    fun testTtlExpirationStopsForwarding() {
        val nodeB = TestNode("PHONE_B")
        nodeB.reachablePeers.add("PHONE_C")

        // Packet arrives at Phone B with TTL = 1 (last hop)
        val packet = createSosPacket(packetId = "GRX-SOS-LAST-HOP", ttl = 1)
        val json = GeoRescueBlePacketCodec.serialize(packet)

        val decision = nodeB.meshNode.ingest(json, fromPeerId = "PHONE_A")
        // Decrementing TTL=1 gives TTL=0 <= 0, so it must NOT be forwarded
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_LOCAL, decision)
        assertEquals(1, nodeB.receivedPackets.size)
        assertEquals(0, nodeB.transmitted.size) // No packet sent to C
    }

    @Test
    fun testDuplicateProtectionRejectsLoops() {
        val nodeB = TestNode("PHONE_B")
        val packet = createSosPacket(packetId = "GRX-SOS-DUP")
        val json = GeoRescueBlePacketCodec.serialize(packet)

        // First arrival from Phone A
        val firstDecision = nodeB.meshNode.ingest(json, fromPeerId = "PHONE_A")
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_QUEUED, firstDecision)

        // Second arrival of identical packetId from Phone C (network loop)
        val secondDecision = nodeB.meshNode.ingest(json, fromPeerId = "PHONE_C")
        assertEquals(GeoRescueBleRelayDecision.DUPLICATE, secondDecision)

        // Third arrival after restart (from durable seen store)
        val nodeBRestarted = TestNode("PHONE_B", seenStore = nodeB.seenStore)
        val thirdDecision = nodeBRestarted.meshNode.ingest(json, fromPeerId = "PHONE_D")
        assertEquals(GeoRescueBleRelayDecision.DUPLICATE, thirdDecision)
    }

    @Test
    fun testNeverEchoBackToSender() {
        val nodeB = TestNode("PHONE_B")
        // Both A and C are reachable
        nodeB.reachablePeers.add("PHONE_A")
        nodeB.reachablePeers.add("PHONE_C")

        val packet = createSosPacket(packetId = "GRX-SOS-ECHO", origin = "PHONE_A", ttl = 5)
        val json = GeoRescueBlePacketCodec.serialize(packet)

        nodeB.meshNode.ingest(json, fromPeerId = "PHONE_A")

        // Node B should ONLY forward to C, never back to A
        assertEquals(1, nodeB.transmitted.size)
        assertEquals("PHONE_C", nodeB.transmitted[0].first)
        assertFalse(nodeB.transmitted.any { it.first == "PHONE_A" })
    }

    @Test
    fun testAutomaticPeerInventorySynchronization() {
        val nodeB = TestNode("PHONE_B")
        // Phone C already has SOS-1 in its durable seen store
        val seenStoreC = FakeSeenStore().apply { saveSeenPacketId("SOS-1", 1_000_000L) }
        val nodeC = TestNode("PHONE_C", seenStore = seenStoreC)

        // Phone B already has SOS-1 and SOS-2 in its cache
        val sos1 = createSosPacket(packetId = "SOS-1", ttl = 4)
        val sos2 = createSosPacket(packetId = "SOS-2", ttl = 4)
        nodeB.meshNode.publish(sos1)
        nodeB.meshNode.publish(sos2)
        nodeB.transmitted.clear() // Clear outbound log

        // Phone B discovers Phone C and synchronizes inventory
        nodeB.reachablePeers.add("PHONE_C")
        val synced = nodeB.meshNode.syncWithPeer("PHONE_C")
        assertTrue(synced)
        assertEquals(1, nodeB.transmitted.size)

        // Phone B sent SYNC_INVENTORY to Phone C
        val (peerTarget, invJson) = nodeB.transmitted[0]
        assertEquals("PHONE_C", peerTarget)
        val invPacket = GeoRescueBlePacketCodec.deserialize(invJson)
        assertNotNull(invPacket)
        assertEquals(GeoRescueBlePacketType.SYNC_INVENTORY, invPacket!!.type)

        // Phone C ingests SYNC_INVENTORY from Phone B
        nodeC.reachablePeers.add("PHONE_B")
        val invDecision = nodeC.meshNode.ingest(invJson, fromPeerId = "PHONE_B")
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_LOCAL, invDecision)

        // Phone C should determine it only needs SOS-2 (since it already has SOS-1)
        // and send SYNC_REQUEST back to Phone B
        assertEquals(1, nodeC.transmitted.size)
        val (cTarget, reqJson) = nodeC.transmitted[0]
        assertEquals("PHONE_B", cTarget)
        val reqPacket = GeoRescueBlePacketCodec.deserialize(reqJson)
        assertNotNull(reqPacket)
        assertEquals(GeoRescueBlePacketType.SYNC_REQUEST, reqPacket!!.type)
        assertEquals("SOS-2", reqPacket.payload["requestedIds"])

        // Phone B ingests SYNC_REQUEST from Phone C
        val reqDecision = nodeB.meshNode.ingest(reqJson, fromPeerId = "PHONE_C")
        assertEquals(GeoRescueBleRelayDecision.ACCEPTED_LOCAL, reqDecision)

        // Phone B sends SOS-2 to Phone C
        val sentSos2 = nodeB.transmitted.find { it.first == "PHONE_C" && it.second.contains("\"type\":\"SOS\"") }
        assertNotNull(sentSos2)

        // Phone C ingests SOS-2
        val sos2Decision = nodeC.meshNode.ingest(sentSos2!!.second, fromPeerId = "PHONE_B")
        assertTrue(
            sos2Decision == GeoRescueBleRelayDecision.ACCEPTED_FORWARDED ||
            sos2Decision == GeoRescueBleRelayDecision.ACCEPTED_QUEUED
        )
        assertTrue(nodeC.seenStore.seen.containsKey("SOS-2"))
    }

    @Test
    fun testBranchedTopology_A_to_B_to_CandD() {
        val nodeA = TestNode("PHONE_A")
        val nodeB = TestNode("PHONE_B")
        val nodeC = TestNode("PHONE_C")
        val nodeD = TestNode("PHONE_D")

        // Topology: A -> B, and B connects to both C and D
        nodeA.reachablePeers.add("PHONE_B")
        nodeB.reachablePeers.add("PHONE_C")
        nodeB.reachablePeers.add("PHONE_D")

        val packet = createSosPacket(packetId = "GRX-BRANCH-1")
        nodeA.meshNode.publish(packet)

        val wireFromA = nodeA.transmitted[0].second
        nodeB.meshNode.ingest(wireFromA, fromPeerId = "PHONE_A")

        // B should have forwarded to BOTH C and D
        assertEquals(2, nodeB.transmitted.size)
        val targets = nodeB.transmitted.map { it.first }.toSet()
        assertEquals(setOf("PHONE_C", "PHONE_D"), targets)

        // Ingest into C and D
        val wireToC = nodeB.transmitted.first { it.first == "PHONE_C" }.second
        val wireToD = nodeB.transmitted.first { it.first == "PHONE_D" }.second

        nodeC.meshNode.ingest(wireToC, fromPeerId = "PHONE_B")
        nodeD.meshNode.ingest(wireToD, fromPeerId = "PHONE_B")

        assertTrue(nodeC.seenStore.seen.containsKey("GRX-BRANCH-1"))
        assertTrue(nodeD.seenStore.seen.containsKey("GRX-BRANCH-1"))
    }
}

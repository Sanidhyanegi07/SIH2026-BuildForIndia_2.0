package com.example.georescux.data.relay

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentSeverity
import com.example.georescux.domain.incident.IncidentType
import com.example.georescux.domain.relay.RelayDecision
import com.example.georescux.domain.relay.RelayEngine
import com.example.georescux.domain.relay.RelayEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BleRelayTransportTest {

    private lateinit var fakeClientAdapter: FakeConnectionsClientAdapter
    private lateinit var connectionManager: RelayConnectionManager
    private lateinit var transport: BleRelayTransport
    private lateinit var relayEngine: RelayEngine

    @Before
    fun setUp() {
        fakeClientAdapter = FakeConnectionsClientAdapter()
        connectionManager = RelayConnectionManager(fakeClientAdapter)
        transport = BleRelayTransport(connectionManager)
        relayEngine = RelayEngine(selfOriginId = "DEVICE_A", transport = transport)
        connectionManager.attachRelayEngine(relayEngine)
    }

    @Test
    fun `starts mesh advertising and discovery`() {
        connectionManager.startMesh("DeviceA")
        assertTrue("Advertising must be active", fakeClientAdapter.isAdvertising)
        assertTrue("Discovery must be active", fakeClientAdapter.isDiscovering)
    }

    @Test
    fun `supports at least 3 simultaneous peer connections for multi-hop mesh`() {
        connectionManager.startMesh("DeviceA")

        // Connect 3 peers (PeerB, PeerC, PeerD)
        fakeClientAdapter.simulateConnection("peerB")
        fakeClientAdapter.simulateConnection("peerC")
        fakeClientAdapter.simulateConnection("peerD")

        val peers = transport.availablePeers()
        assertEquals(3, peers.size)
        assertTrue(peers.containsAll(listOf("peerB", "peerC", "peerD")))
    }

    @Test
    fun `serializes and forwards local event to all available connected peers`() {
        connectionManager.startMesh("DeviceA")
        fakeClientAdapter.simulateConnection("peerB")
        fakeClientAdapter.simulateConnection("peerC")

        val incident = IncidentEvent(
            eventId = "sos_001",
            originId = "DEVICE_A",
            type = IncidentType.SOS,
            occurredAtMs = System.currentTimeMillis(),
            severity = IncidentSeverity.CRITICAL,
        )

        val decision = relayEngine.onLocalEvent(incident)

        assertEquals(RelayDecision.ACCEPTED_FORWARDED, decision)
        assertEquals(2, fakeClientAdapter.sentPayloads.size)
        assertTrue(fakeClientAdapter.sentPayloads.any { it.first == "peerB" })
        assertTrue(fakeClientAdapter.sentPayloads.any { it.first == "peerC" })
    }

    @Test
    fun `deserializes incoming peer payload and ingests into RelayEngine`() {
        connectionManager.startMesh("DeviceA")
        fakeClientAdapter.simulateConnection("peerB")

        var receivedEnvelope: RelayEnvelope? = null
        connectionManager.setOnEventReceivedListener { envelope ->
            receivedEnvelope = envelope
        }

        val incomingEvent = IncidentEvent(
            eventId = "sos_002",
            originId = "DEVICE_B",
            type = IncidentType.SOS,
            occurredAtMs = System.currentTimeMillis(),
            severity = IncidentSeverity.CRITICAL,
        )
        val envelope = RelayEnvelope(event = incomingEvent, payload = incomingEvent.canonicalString())
        val serializedPayload = RelayEnvelopeSerializer.serialize(envelope)

        fakeClientAdapter.simulateIncomingPayload("peerB", serializedPayload.toByteArray(Charsets.UTF_8))

        assertTrue("Received envelope must be ingested", receivedEnvelope != null)
        assertEquals("sos_002", receivedEnvelope!!.event.eventId)
    }

    private class FakeConnectionsClientAdapter : ConnectionsClientAdapter {
        var isAdvertising = false
        var isDiscovering = false
        var activeListener: ConnectionLifecycleListener? = null
        val sentPayloads = mutableListOf<Pair<String, String>>()

        override fun startAdvertising(serviceId: String, localEndpointName: String, listener: ConnectionLifecycleListener): Boolean {
            isAdvertising = true
            activeListener = listener
            return true
        }

        override fun startDiscovery(serviceId: String, listener: EndpointDiscoveryListener): Boolean {
            isDiscovering = true
            return true
        }

        override fun sendPayload(endpointId: String, payloadBytes: ByteArray): Boolean {
            sentPayloads.add(endpointId to String(payloadBytes, Charsets.UTF_8))
            return true
        }

        override fun stopAdvertising() { isAdvertising = false }
        override fun stopDiscovery() { isDiscovering = false }
        override fun disconnectFromEndpoint(endpointId: String) {
            activeListener?.onDisconnected(endpointId)
        }

        fun simulateConnection(endpointId: String) {
            activeListener?.onConnectionInitiated(endpointId, endpointId)
            activeListener?.onConnectionResult(endpointId, 0) // SUCCESS
        }

        fun simulateIncomingPayload(endpointId: String, bytes: ByteArray) {
            activeListener?.onPayloadReceived(endpointId, bytes)
        }
    }
}

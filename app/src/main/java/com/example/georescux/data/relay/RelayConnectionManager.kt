package com.example.georescux.data.relay

import com.example.georescux.domain.relay.RelayEngine
import com.example.georescux.domain.relay.RelayEnvelope
import java.util.Collections

/**
 * Manages peer connection lifecycles, active peer sets, payload transfer encoding/decoding,
 * and passes incoming envelopes to [RelayEngine].
 */
class RelayConnectionManager(
    private val clientAdapter: ConnectionsClientAdapter,
    private val serviceId: String = SERVICE_ID,
    private var onEventReceivedListener: ((RelayEnvelope) -> Unit)? = null,
) {

    private val connectedPeersSet = Collections.synchronizedSet(HashSet<String>())
    private var relayEngine: RelayEngine? = null

    fun attachRelayEngine(engine: RelayEngine) {
        this.relayEngine = engine
    }

    fun setOnEventReceivedListener(listener: (RelayEnvelope) -> Unit) {
        this.onEventReceivedListener = listener
    }

    fun connectedPeers(): List<String> = synchronized(connectedPeersSet) {
        connectedPeersSet.toList()
    }

    fun startMesh(localEndpointName: String) {
        val lifecycleListener = object : ConnectionLifecycleListener {
            override fun onConnectionInitiated(endpointId: String, endpointName: String) {}

            override fun onConnectionResult(endpointId: String, statusCode: Int) {
                if (statusCode == 0) { // SUCCESS
                    connectedPeersSet.add(endpointId)
                    relayEngine?.onPeerAvailable(endpointId)
                } else {
                    connectedPeersSet.remove(endpointId)
                }
            }

            override fun onDisconnected(endpointId: String) {
                connectedPeersSet.remove(endpointId)
            }

            override fun onPayloadReceived(endpointId: String, payloadBytes: ByteArray) {
                val payloadString = String(payloadBytes, Charsets.UTF_8)
                val envelope = RelayEnvelopeSerializer.deserialize(payloadString, receivingPeerId = endpointId)
                if (envelope != null) {
                    onEventReceivedListener?.invoke(envelope)
                    relayEngine?.onEventReceived(envelope)
                }
            }
        }

        val discoveryListener = object : EndpointDiscoveryListener {
            override fun onEndpointFound(endpointId: String, endpointName: String, serviceId: String) {}
            override fun onEndpointLost(endpointId: String) {
                connectedPeersSet.remove(endpointId)
            }
        }

        clientAdapter.startAdvertising(serviceId, localEndpointName, lifecycleListener)
        clientAdapter.startDiscovery(serviceId, discoveryListener)
    }

    fun sendPayload(peerId: String, payloadString: String): Boolean {
        if (!connectedPeersSet.contains(peerId)) return false
        val bytes = payloadString.toByteArray(Charsets.UTF_8)
        return clientAdapter.sendPayload(peerId, bytes)
    }

    fun stopMesh() {
        clientAdapter.stopAdvertising()
        clientAdapter.stopDiscovery()
        connectedPeersSet.clear()
    }

    companion object {
        const val SERVICE_ID = "com.example.georescux.BLE_MESH"
        const val MAX_SIMULTANEOUS_PEERS = 8 // Supports multi-hop cluster topology (A -> B -> C)
    }
}

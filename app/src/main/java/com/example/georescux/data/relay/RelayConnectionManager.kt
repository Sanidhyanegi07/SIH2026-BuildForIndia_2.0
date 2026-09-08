package com.example.georescux.data.relay

import com.example.georescux.domain.relay.RelayEngine
import com.example.georescux.domain.relay.RelayEnvelope
import java.util.Collections

/**
 * Manages peer connection lifecycles, active peer sets, payload transfer encoding/decoding,
 * and passes incoming envelopes to [RelayEngine].
 *
 * Bug 1 fix: [startMesh] now passes the SAME [ConnectionLifecycleListener] to BOTH
 * [ConnectionsClientAdapter.startAdvertising] and [ConnectionsClientAdapter.startDiscovery],
 * so [GoogleNearbyClientAdapter.activeLifecycleListener] is always non-null when a payload
 * arrives — regardless of which device initiated the connection.
 *
 * Bug 4 fix: [startMesh] calls [stopMesh] first when already running, so a stale [isRunning]
 * flag (left over from an internal Nearby crash) can never permanently block restarts.
 * [stopMesh] no longer has an early-return guard, so it is always safe to call.
 */
class RelayConnectionManager(
    private val clientAdapter: ConnectionsClientAdapter,
    private val serviceId: String = SERVICE_ID,
    private var onEventReceivedListener: ((RelayEnvelope) -> Unit)? = null,
) {

    private val connectedPeersSet = Collections.synchronizedSet(HashSet<String>())
    private var relayEngine: RelayEngine? = null

    @Volatile
    var isRunning = false
        private set

    val isMeshRunning: Boolean get() = isRunning

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
        // FIX Bug 4: stop first so a stale isRunning (from an internal Nearby failure that
        // we could not observe synchronously) never silently blocks a restart.
        if (isRunning) stopMesh()
        isRunning = true

        val lifecycleListener = object : ConnectionLifecycleListener {
            override fun onConnectionInitiated(endpointId: String, endpointName: String) {}

            override fun onConnectionResult(endpointId: String, statusCode: Int) {
                if (statusCode == 0) { // STATUS_OK
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
                val envelope = RelayEnvelopeSerializer.deserialize(
                    payloadString,
                    receivingPeerId = endpointId,
                )
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

        // FIX Bug 1: pass the SAME lifecycleListener to startDiscovery so
        // GoogleNearbyClientAdapter.activeLifecycleListener is always set before Nearby
        // can deliver any incoming payload via payloadCallback.
        val advertiseOk = clientAdapter.startAdvertising(serviceId, localEndpointName, lifecycleListener)
        val discoverOk = clientAdapter.startDiscovery(serviceId, discoveryListener, lifecycleListener)

        // If both sides fail immediately (e.g. SecurityException before async work starts),
        // reset so callers can retry after the user grants permissions.
        if (!advertiseOk && !discoverOk) {
            isRunning = false
        }
    }

    fun sendPayload(peerId: String, payloadString: String): Boolean {
        if (!connectedPeersSet.contains(peerId)) return false
        val bytes = payloadString.toByteArray(Charsets.UTF_8)
        return clientAdapter.sendPayload(peerId, bytes)
    }

    fun stopMesh() {
        // FIX Bug 4: removed the old `if (!isRunning) return` guard — stopMesh must always
        // clean up state safely, even if isRunning was left true by an unobserved Nearby failure.
        isRunning = false
        clientAdapter.stopAdvertising()
        clientAdapter.stopDiscovery()
        connectedPeersSet.clear()
    }

    companion object {
        const val SERVICE_ID = "com.example.georescux.BLE_MESH"
        const val MAX_SIMULTANEOUS_PEERS = 8 // Supports multi-hop cluster topology (A -> B -> C)
    }
}

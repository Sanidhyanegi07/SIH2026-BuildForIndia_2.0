package com.example.georescux.data.relay

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/**
 * Real Google Nearby Connections API implementation of [ConnectionsClientAdapter].
 * Uses Strategy.P2P_CLUSTER so a device can simultaneously advertise and discover (multi-hop mesh).
 *
 * Bug 1 fix: [activeLifecycleListener] is now set in BOTH [startAdvertising] AND [startDiscovery].
 * Previously it was only set in [startAdvertising], so [payloadCallback] had a null target
 * whenever the connection was initiated from the discoverer side — causing all inbound SOS
 * payloads to be silently dropped on the receiving device.
 */
class GoogleNearbyClientAdapter(
    context: Context,
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext),
    /**
     * Called when advertising or discovery fails asynchronously (after the call returns true),
     * so [RelayConnectionManager] can reset its [RelayConnectionManager.isRunning] flag and
     * allow a clean retry.
     */
    var onMeshFailure: (() -> Unit)? = null,
) : ConnectionsClientAdapter {

    /**
     * Volatile so both advertising and discovery paths can update it safely from different
     * threads/callbacks. [payloadCallback] always reads this field — it must never be null
     * when a payload arrives.
     */
    @Volatile
    private var activeLifecycleListener: ConnectionLifecycleListener? = null
    private var activeEndpointName: String = "GeoRescuXDevice"

    /**
     * Single shared payload sink. Forwards bytes to [activeLifecycleListener] which is
     * guaranteed to be non-null by the time any payload arrives (both startAdvertising and
     * startDiscovery now set the field before Nearby can fire callbacks).
     */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            activeLifecycleListener?.onPayloadReceived(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    override fun startAdvertising(
        serviceId: String,
        localEndpointName: String,
        listener: ConnectionLifecycleListener,
    ): Boolean {
        activeEndpointName = localEndpointName
        // FIX Bug 1: set BEFORE starting — Nearby can fire onConnectionInitiated synchronously.
        activeLifecycleListener = listener
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val callback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                listener.onConnectionInitiated(endpointId, info.endpointName)
                try {
                    client.acceptConnection(endpointId, payloadCallback)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to accept connection due to missing permission", e)
                }
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                listener.onConnectionResult(endpointId, result.status.statusCode)
            }

            override fun onDisconnected(endpointId: String) {
                listener.onDisconnected(endpointId)
            }
        }
        return try {
            client.startAdvertising(localEndpointName, serviceId, callback, options)
                .addOnSuccessListener { Log.i(TAG, "Advertising started for service: $serviceId") }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to start advertising: ${e.message}", e)
                    onMeshFailure?.invoke()
                }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start BLE advertising due to missing permission", e)
            onMeshFailure?.invoke()
            false
        }
    }

    override fun startDiscovery(
        serviceId: String,
        discoveryListener: EndpointDiscoveryListener,
        lifecycleListener: ConnectionLifecycleListener,
    ): Boolean {
        // FIX Bug 1: always (re-)set activeLifecycleListener so payloadCallback has a valid
        // target even when the connection was initiated from this device's discoverer role
        // (before startAdvertising's async task fires its success callback).
        activeLifecycleListener = lifecycleListener

        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val callback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                discoveryListener.onEndpointFound(endpointId, info.endpointName, info.serviceId)
                try {
                    client.requestConnection(
                        activeEndpointName,
                        endpointId,
                        object : ConnectionLifecycleCallback() {
                            override fun onConnectionInitiated(
                                endpointId: String,
                                info: ConnectionInfo,
                            ) {
                                lifecycleListener.onConnectionInitiated(endpointId, info.endpointName)
                                try {
                                    client.acceptConnection(endpointId, payloadCallback)
                                } catch (e: SecurityException) {
                                    Log.e(TAG, "Failed to accept connection due to missing permission", e)
                                }
                            }

                            override fun onConnectionResult(
                                endpointId: String,
                                result: ConnectionResolution,
                            ) {
                                lifecycleListener.onConnectionResult(endpointId, result.status.statusCode)
                            }

                            override fun onDisconnected(endpointId: String) {
                                lifecycleListener.onDisconnected(endpointId)
                            }
                        },
                    )
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to request connection due to missing permission", e)
                }
            }

            override fun onEndpointLost(endpointId: String) {
                discoveryListener.onEndpointLost(endpointId)
            }
        }
        return try {
            client.startDiscovery(serviceId, callback, options)
                .addOnSuccessListener { Log.i(TAG, "Discovery started for service: $serviceId") }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to start discovery: ${e.message}", e)
                    onMeshFailure?.invoke()
                }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start BLE discovery due to missing permission", e)
            onMeshFailure?.invoke()
            false
        }
    }

    override fun sendPayload(endpointId: String, payloadBytes: ByteArray): Boolean {
        return try {
            client.sendPayload(endpointId, Payload.fromBytes(payloadBytes))
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to send payload due to missing permission", e)
            false
        }
    }

    override fun stopAdvertising() {
        try {
            client.stopAdvertising()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising", e)
        }
    }

    override fun stopDiscovery() {
        try {
            client.stopDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping discovery", e)
        }
    }

    override fun disconnectFromEndpoint(endpointId: String) {
        try {
            client.disconnectFromEndpoint(endpointId)
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting from endpoint", e)
        }
    }

    companion object {
        private const val TAG = "GoogleNearbyClient"
    }
}

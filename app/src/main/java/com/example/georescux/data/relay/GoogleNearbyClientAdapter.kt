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
 */
class GoogleNearbyClientAdapter(
    context: Context,
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext),
) : ConnectionsClientAdapter {

    private var activeLifecycleListener: ConnectionLifecycleListener? = null
    private var activeEndpointName: String = "GeoRescuXDevice"

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
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start BLE advertising due to missing permission", e)
            false
        }
    }

    override fun startDiscovery(serviceId: String, listener: EndpointDiscoveryListener): Boolean {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val callback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                listener.onEndpointFound(endpointId, info.endpointName, info.serviceId)
                val lifecycleListener = activeLifecycleListener ?: return
                try {
                    client.requestConnection(activeEndpointName, endpointId, object : ConnectionLifecycleCallback() {
                        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                            lifecycleListener.onConnectionInitiated(endpointId, info.endpointName)
                            try {
                                client.acceptConnection(endpointId, payloadCallback)
                            } catch (e: SecurityException) {
                                Log.e(TAG, "Failed to accept connection due to missing permission", e)
                            }
                        }

                        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                            lifecycleListener.onConnectionResult(endpointId, result.status.statusCode)
                        }

                        override fun onDisconnected(endpointId: String) {
                            lifecycleListener.onDisconnected(endpointId)
                        }
                    })
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to request connection due to missing permission", e)
                }
            }

            override fun onEndpointLost(endpointId: String) {
                listener.onEndpointLost(endpointId)
            }
        }
        return try {
            client.startDiscovery(serviceId, callback, options)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start BLE discovery due to missing permission", e)
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

package com.example.georescux.data.relay

import android.content.Context
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
        activeLifecycleListener = listener
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val callback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                listener.onConnectionInitiated(endpointId, info.endpointName)
                client.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                listener.onConnectionResult(endpointId, result.status.statusCode)
            }

            override fun onDisconnected(endpointId: String) {
                listener.onDisconnected(endpointId)
            }
        }
        client.startAdvertising(localEndpointName, serviceId, callback, options)
        return true
    }

    override fun startDiscovery(serviceId: String, listener: EndpointDiscoveryListener): Boolean {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val callback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                listener.onEndpointFound(endpointId, info.endpointName, info.serviceId)
                val lifecycleListener = activeLifecycleListener ?: return
                client.requestConnection(endpointId, endpointId, object : ConnectionLifecycleCallback() {
                    override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                        lifecycleListener.onConnectionInitiated(endpointId, info.endpointName)
                        client.acceptConnection(endpointId, payloadCallback)
                    }

                    override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                        lifecycleListener.onConnectionResult(endpointId, result.status.statusCode)
                    }

                    override fun onDisconnected(endpointId: String) {
                        lifecycleListener.onDisconnected(endpointId)
                    }
                })
            }

            override fun onEndpointLost(endpointId: String) {
                listener.onEndpointLost(endpointId)
            }
        }
        client.startDiscovery(serviceId, callback, options)
        return true
    }

    override fun sendPayload(endpointId: String, payloadBytes: ByteArray): Boolean {
        client.sendPayload(endpointId, Payload.fromBytes(payloadBytes))
        return true
    }

    override fun stopAdvertising() {
        client.stopAdvertising()
    }

    override fun stopDiscovery() {
        client.stopDiscovery()
    }

    override fun disconnectFromEndpoint(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
    }
}

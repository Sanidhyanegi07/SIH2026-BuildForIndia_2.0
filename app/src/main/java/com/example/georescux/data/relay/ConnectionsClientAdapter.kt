package com.example.georescux.data.relay

/**
 * Abstraction boundary over Nearby Connections Client API to allow pure JVM unit testing.
 */
interface ConnectionsClientAdapter {
    fun startAdvertising(serviceId: String, localEndpointName: String, listener: ConnectionLifecycleListener): Boolean
    fun startDiscovery(serviceId: String, listener: EndpointDiscoveryListener): Boolean
    fun sendPayload(endpointId: String, payloadBytes: ByteArray): Boolean
    fun stopAdvertising()
    fun stopDiscovery()
    fun disconnectFromEndpoint(endpointId: String)
}

interface ConnectionLifecycleListener {
    fun onConnectionInitiated(endpointId: String, endpointName: String)
    fun onConnectionResult(endpointId: String, statusCode: Int)
    fun onDisconnected(endpointId: String)
    fun onPayloadReceived(endpointId: String, payloadBytes: ByteArray)
}

interface EndpointDiscoveryListener {
    fun onEndpointFound(endpointId: String, endpointName: String, serviceId: String)
    fun onEndpointLost(endpointId: String)
}

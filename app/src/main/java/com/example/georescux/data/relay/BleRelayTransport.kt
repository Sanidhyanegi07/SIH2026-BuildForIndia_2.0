package com.example.georescux.data.relay

import com.example.georescux.domain.relay.RelayTransport

/**
 * Android BLE / Nearby Connections implementation of [RelayTransport].
 */
class BleRelayTransport(
    private val connectionManager: RelayConnectionManager,
) : RelayTransport {

    override fun availablePeers(): List<String> = connectionManager.connectedPeers()

    override fun send(peerId: String, payload: String): Boolean =
        connectionManager.sendPayload(peerId, payload)
}

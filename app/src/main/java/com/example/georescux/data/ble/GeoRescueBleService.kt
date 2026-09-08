package com.example.georescux.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import com.example.georescux.domain.ble.GeoRescueBleDiagnostics

/**
 * Phase 5 — the GeoRescuX GATT SERVER (peripheral role).
 *
 * Hosts the dedicated emergency service (see [GeoRescueBleProfile]):
 * - RX characteristic: remote clients write packet chunks here; this device
 *   RECEIVES data through it.
 * - TX characteristic: this device NOTIFIES subscribed clients here,
 *   TRANSMITTING data to them.
 *
 * The framer reassembly happens in the manager; this class only moves
 * bytes and reports connection lifecycle.
 */
@Suppress("MissingPermission")
class GeoRescueBleService(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
) {

    interface GattServerListener {
        /** A remote device (client) connected to this server. */
        fun onServerDeviceConnected(device: BluetoothDevice)

        /** A remote device disconnected from this server. */
        fun onServerDeviceDisconnected(device: BluetoothDevice)

        /** A client enabled notifications on TX (it can now receive notifies). */
        fun onClientSubscribed(device: BluetoothDevice)

        /** A client disabled notifications on TX. */
        fun onClientUnsubscribed(device: BluetoothDevice)

        /** Raw bytes received on RX from a client. */
        fun onBytesReceived(device: BluetoothDevice, bytes: ByteArray)

        /** Fragmented open/open fails — surfaced for the failure classifier. */
        fun onServerOpenFailed(status: Int)
    }

    var gattServer: BluetoothGattServer? = null
        private set

    /** Clients that enabled notifications on TX (device address -> device). */
    private val subscribedClients = HashMap<String, BluetoothDevice>()

    private var listener: GattServerListener? = null

    /** Address-keyed view for the manager/sink. */
    fun subscribedClientAddresses(): Set<String> = subscribedClients.keys.toSet()

    /**
     * Opens the GATT server and registers the GeoRescuX service. Returns
     * null (with a logged failure) instead of throwing on any problem.
     */
    fun open(listener: GattServerListener): BluetoothGattServer? {
        val server = try {
            bluetoothManager.openGattServer(context.applicationContext, serverCallback)
        } catch (e: SecurityException) {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_INIT_FAILED,
                "component=GATT_SERVER code=SECURITY_EXCEPTION detail=${e.message}"
            )
            return null
        } catch (e: NullPointerException) {
            // Some OEM stacks throw when the adapter is mid-shutdown.
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_INIT_FAILED,
                "component=GATT_SERVER code=ADAPTER_UNAVAILABLE detail=${e.message}"
            )
            return null
        }
        if (server == null) {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_INIT_FAILED,
                "component=GATT_SERVER code=OPEN_RETURNED_NULL"
            )
            return null
        }
        return try {
            server.addService(GeoRescueBleProfile.buildService())
            gattServer = server
            this.listener = listener
            server
        } catch (e: Exception) {
            try {
                server.close()
            } catch (_: Exception) {
            }
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_INIT_FAILED,
                "component=GATT_SERVER code=ADD_SERVICE_FAILED detail=${e.message}"
            )
            null
        }
    }

    /**
     * Notifies one chunk of a packet frame to a single subscribed client
     * (looked up by address). Returns false when the client is unknown,
     * unsubscribed, or the stack rejects the notify — the manager then
     * keeps the packet queued for this peer.
     */
    fun notifyTo(address: String, bytes: ByteArray): Boolean {
        val server = gattServer ?: return false
        val device = synchronized(subscribedClients) { subscribedClients[address] } ?: return false
        val tx = server.getService(GeoRescueBleProfile.SERVICE_UUID)
            ?.getCharacteristic(GeoRescueBleProfile.TX_CHARACTERISTIC_UUID) ?: return false
        return try {
            val accepted: Boolean =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, tx, false, bytes) ==
                        android.bluetooth.BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    tx.value = bytes
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, tx, false)
                }
            if (!accepted) {
                GeoRescueBleDiagnostics.warn(
                    GeoRescueBleDiagnostics.WRITE_FAILED,
                    "role=SERVER_NOTIFY address=$address code=NOT_ACCEPTED"
                )
            }
            accepted
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.warn(
                GeoRescueBleDiagnostics.WRITE_FAILED,
                "role=SERVER_NOTIFY address=$address detail=${e.message}"
            )
            false
        }
    }

    fun close() {
        try {
            gattServer?.close()
            GeoRescueBleDiagnostics.info(GeoRescueBleDiagnostics.DISCONNECTED, "role=SERVER_CLOSED")
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.warn(GeoRescueBleDiagnostics.DISCONNECTED, "role=SERVER_CLOSE_FAILED detail=${e.message}")
        }
        gattServer = null
        synchronized(subscribedClients) { subscribedClients.clear() }
        listener = null
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.GATT_CONNECTED,
                    "role=SERVER address=$address status=$status"
                )
                listener?.onServerDeviceConnected(device)
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.DISCONNECTED,
                    "role=SERVER address=$address status=$status"
                )
                synchronized(subscribedClients) { subscribedClients.remove(address) }
                listener?.onServerDeviceDisconnected(device)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == GeoRescueBleProfile.RX_CHARACTERISTIC_UUID) {
                GeoRescueBleDiagnostics.verbose(
                    GeoRescueBleDiagnostics.DATA_RECEIVED,
                    "role=SERVER address=${device.address} bytes=${value.size}"
                )
                listener?.onBytesReceived(device, value)
            }
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(
                        device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS,
                        offset, value
                    )
                } catch (e: Exception) {
                    GeoRescueBleDiagnostics.warn(
                        GeoRescueBleDiagnostics.BLE_ERROR,
                        "role=SERVER code=RESPONSE_FAILED detail=${e.message}"
                    )
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == GeoRescueBleProfile.CCCD_UUID &&
                descriptor.characteristic?.uuid == GeoRescueBleProfile.TX_CHARACTERISTIC_UUID
            ) {
                val subscribing = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (subscribing) {
                    synchronized(subscribedClients) { subscribedClients[device.address] = device }
                    GeoRescueBleDiagnostics.info(
                        GeoRescueBleDiagnostics.NOTIFICATION_ENABLED,
                        "role=SERVER address=${device.address}"
                    )
                    listener?.onClientSubscribed(device)
                } else {
                    synchronized(subscribedClients) { subscribedClients.remove(device.address) }
                    listener?.onClientUnsubscribed(device)
                }
            }
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(
                        device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS,
                        offset, value
                    )
                } catch (e: Exception) {
                    GeoRescueBleDiagnostics.warn(
                        GeoRescueBleDiagnostics.BLE_ERROR,
                        "role=SERVER code=DESCRIPTOR_RESPONSE_FAILED detail=${e.message}"
                    )
                }
            }
        }

        override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService) {
            if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS &&
                service.uuid == GeoRescueBleProfile.SERVICE_UUID
            ) {
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.GEORESCUE_SERVICE_FOUND,
                    "role=SERVER registered=${service.uuid}"
                )
            } else {
                GeoRescueBleDiagnostics.error(
                    GeoRescueBleDiagnostics.BLE_INIT_FAILED,
                    "component=GATT_SERVER code=SERVICE_ADD_STATUS_$status"
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                GeoRescueBleDiagnostics.warn(
                    GeoRescueBleDiagnostics.WRITE_FAILED,
                    "role=SERVER_NOTIFY address=${device.address} status=$status"
                )
            }
        }
    }
}

package com.example.georescux.data.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattService
import java.util.UUID

/**
 * The dedicated GeoRescuX GATT profile — stable UUID constants for the
 * custom emergency service. Generated for this project (no official
 * assignment exists); the values are hard-coded so every GeoRescuX device
 * speaks the same profile.
 *
 * Semantics (from the point of view of the GATT SERVER hosting the service):
 * - RX_CHARACTERISTIC_UUID: clients WRITE packets here ("received by the device").
 * - TX_CHARACTERISTIC_UUID: the server NOTIFYS packets here ("transmitted to clients").
 * - The standard Client Characteristic Configuration descriptor (0x2902)
 *   sits on TX to let clients enable notifications.
 *
 * Both ends of a connection run the service (each device is GATT server AND
 * GATT client), so packets flow in both directions.
 */
object GeoRescueBleProfile {

    val SERVICE_UUID: UUID = UUID.fromString("f5a6bd01-2c3e-4d5a-9b7f-1e3d5c7a9b01")
    val RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("f5a6bd01-2c3e-4d5a-9b7f-1e3d5c7a9b02")
    val TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("f5a6bd01-2c3e-4d5a-9b7f-1e3d5c7a9b03")

    /** Standard CCCD used to enable notifications on TX. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Properties of the RX characteristic: clients may write with or without response. */
    private const val RX_PROPERTIES =
        BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

    /** Properties of the TX characteristic: the server notifies subscribed clients. */
    private const val TX_PROPERTIES = BluetoothGattCharacteristic.PROPERTY_NOTIFY

    /** Builds the complete [BluetoothGattService] with RX + TX (+ CCCD on TX). */
    fun buildService(): BluetoothGattService =
        BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    RX_CHARACTERISTIC_UUID,
                    RX_PROPERTIES,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    TX_CHARACTERISTIC_UUID,
                    TX_PROPERTIES,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                ).apply {
                    addDescriptor(
                        BluetoothGattDescriptor(
                            CCCD_UUID,
                            BluetoothGattDescriptor.PERMISSION_READ or
                                BluetoothGattDescriptor.PERMISSION_WRITE,
                        )
                    )
                }
            )
        }
}

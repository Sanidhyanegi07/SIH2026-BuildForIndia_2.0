package com.example.georescux.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.example.georescux.domain.ble.GeoRescueBleConnectionStateMachine
import com.example.georescux.domain.ble.GeoRescueBleDiagnostics
import com.example.georescux.domain.ble.GeoRescueBleFramer
import com.example.georescux.domain.ble.GeoRescueBlePacket
import com.example.georescux.domain.ble.GeoRescueBleState

/**
 * Phase 5 — the GeoRescuX GATT CLIENT (central role) for ONE peer.
 *
 * Implements the full documented connection flow with an explicit state
 * transition at every stage:
 *
 *   Scanner -> GeoRescuX device discovered -> Connect -> Discover GATT
 *   services -> Find GeoRescuX service -> Find RX/TX characteristics ->
 *   Enable notifications -> READY -> Exchange data -> Disconnect safely.
 *
 * Packet frames are written to the server's RX characteristic in ordered
 * chunks; incoming TX notifications are reassembled (see GeoRescueBleFramer).
 *
 * Both the deprecated and the API-33+ forms of onCharacteristicChanged /
 * writeCharacteristic are handled explicitly: on any given device exactly
 * one of the pair is invoked by the platform, which keeps minSdk 24
 * devices working alongside Android 13+ ones.
 */
@Suppress("MissingPermission")
class GeoRescueBleConnection(
    private val appContext: Context,
    val remoteDevice: BluetoothDevice,
    private val listener: ConnectionListener,
) {

    /** Callback surface for the manager. */
    interface ConnectionListener {
        fun onConnectionReady(connection: GeoRescueBleConnection)

        fun onDisconnected(connection: GeoRescueBleConnection)

        fun onConnectionFailed(connection: GeoRescueBleConnection, status: Int)

        fun onBytesReceived(connection: GeoRescueBleConnection, bytes: ByteArray)
    }

    /** Whether the remote GeoRescuX service was found with both RX and TX. */
    var serviceDiscovered = false
        private set

    var rxFound = false
        private set

    var txFound = false
        private set

    var notificationsEnabled = false
        private set

    var negotiatedMtu = DEFAULT_MTU
        private set

    val deviceAddress: String get() = remoteDevice.address

    private var gatt: BluetoothGatt? = null
    private val stateMachine = GeoRescueBleConnectionStateMachine(GeoRescueBleState.CONNECTING)
    private val framer = GeoRescueBleFramer()
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private var closed = false
    private var remoteRx: BluetoothGattCharacteristic? = null

    /**
     * Initiates the GATT connection (direct connect, LE transport). Returns
     * false synchronously when the stack rejects the request outright.
     */
    fun connect(): Boolean {
        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gattIn: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    stateMachine.transitionTo(GeoRescueBleState.CONNECTED)
                    GeoRescueBleDiagnostics.info(
                        GeoRescueBleDiagnostics.GATT_CONNECTED,
                        "role=CLIENT address=$deviceAddress status=$status"
                    )
                    // MTU first: a larger MTU reduces chunk counts for packet frames.
                    if (!gattIn.requestMtu(REQUESTED_MTU)) {
                        // Fall through to discovery at the default MTU.
                        onMtuSettled(gattIn, DEFAULT_MTU)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (closed || stateMachine.state == GeoRescueBleState.DISCONNECTING) {
                        stateMachine.transitionTo(GeoRescueBleState.DISCONNECTED)
                        GeoRescueBleDiagnostics.info(
                            GeoRescueBleDiagnostics.DISCONNECTED,
                            "role=CLIENT address=$deviceAddress status=$status"
                        )
                        listener.onDisconnected(this@GeoRescueBleConnection)
                    } else {
                        stateMachine.transitionTo(GeoRescueBleState.ERROR)
                        GeoRescueBleDiagnostics.error(
                            GeoRescueBleDiagnostics.CONNECTION_FAILED,
                            "role=CLIENT address=$deviceAddress status=$status phase=UNEXPECTED_DISCONNECT"
                        )
                        listener.onConnectionFailed(this@GeoRescueBleConnection, status)
                    }
                    closeGattQuietly()
                }
            }

            override fun onMtuChanged(gattIn: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    negotiatedMtu = mtu
                    GeoRescueBleDiagnostics.info(
                        GeoRescueBleDiagnostics.MTU_NEGOTIATED,
                        "role=CLIENT address=$deviceAddress mtu=$mtu"
                    )
                } else {
                    negotiatedMtu = DEFAULT_MTU
                }
                onMtuSettled(gattIn, negotiatedMtu)
            }

            override fun onServicesDiscovered(gattIn: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stateMachine.transitionTo(GeoRescueBleState.ERROR)
                    GeoRescueBleDiagnostics.error(
                        GeoRescueBleDiagnostics.SERVICE_DISCOVERY_FAILED,
                        "address=$deviceAddress status=$status"
                    )
                    listener.onConnectionFailed(this@GeoRescueBleConnection, status)
                    return
                }
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.SERVICE_DISCOVERY_SUCCESS,
                    "address=$deviceAddress services=${gattIn.services.size}"
                )
                val service = gattIn.getService(GeoRescueBleProfile.SERVICE_UUID)
                if (service == null) {
                    stateMachine.transitionTo(GeoRescueBleState.ERROR)
                    GeoRescueBleDiagnostics.warn(
                        GeoRescueBleDiagnostics.SERVICE_DISCOVERY_FAILED,
                        "address=$deviceAddress code=GEORESCUX_SERVICE_MISSING"
                    )
                    listener.onConnectionFailed(this@GeoRescueBleConnection, ERROR_NO_SERVICE)
                    return
                }
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.GEORESCUE_SERVICE_FOUND,
                    "role=CLIENT address=$deviceAddress service=${service.uuid}"
                )
                val rx = service.getCharacteristic(GeoRescueBleProfile.RX_CHARACTERISTIC_UUID)
                val tx = service.getCharacteristic(GeoRescueBleProfile.TX_CHARACTERISTIC_UUID)
                if (rx == null || tx == null) {
                    stateMachine.transitionTo(GeoRescueBleState.ERROR)
                    listener.onConnectionFailed(this@GeoRescueBleConnection, ERROR_NO_CHARACTERISTICS)
                    return
                }
                remoteRx = rx
                rxFound = true
                txFound = true
                serviceDiscovered = true
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.RX_CHARACTERISTIC_FOUND,
                    "address=$deviceAddress uuid=${GeoRescueBleProfile.RX_CHARACTERISTIC_UUID}"
                )
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.TX_CHARACTERISTIC_FOUND,
                    "address=$deviceAddress uuid=${GeoRescueBleProfile.TX_CHARACTERISTIC_UUID}"
                )
                val cccd = tx.getDescriptor(GeoRescueBleProfile.CCCD_UUID)
                if (cccd == null) {
                    stateMachine.transitionTo(GeoRescueBleState.ERROR)
                    listener.onConnectionFailed(this@GeoRescueBleConnection, ERROR_NO_CCCD)
                    return
                }
                val initiated = try {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    writeDescriptorCompat(gattIn, cccd)
                } catch (e: Exception) {
                    GeoRescueBleDiagnostics.error(
                        GeoRescueBleDiagnostics.NOTIFICATION_FAILED,
                        "address=$deviceAddress code=DESCRIPTOR_WRITE_EXCEPTION detail=${e.message}"
                    )
                    false
                }
                if (!initiated) {
                    stateMachine.transitionTo(GeoRescueBleState.ERROR)
                    listener.onConnectionFailed(this@GeoRescueBleConnection, ERROR_CCCD_WRITE_FAILED)
                }
            }

            override fun onDescriptorWrite(gattIn: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != GeoRescueBleProfile.CCCD_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stateMachine.transitionTo(GeoRescueBleState.ERROR)
                    GeoRescueBleDiagnostics.error(
                        GeoRescueBleDiagnostics.NOTIFICATION_FAILED,
                        "address=$deviceAddress status=$status"
                    )
                    listener.onConnectionFailed(this@GeoRescueBleConnection, status)
                    return
                }
                notificationsEnabled = true
                stateMachine.transitionTo(GeoRescueBleState.READY)
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.NOTIFICATION_ENABLED,
                    "role=CLIENT address=$deviceAddress"
                )
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.CONNECTION_SUCCESS,
                    "address=$deviceAddress state=READY"
                )
                listener.onConnectionReady(this@GeoRescueBleConnection)
            }

            override fun onCharacteristicWrite(
                gattIn: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid != GeoRescueBleProfile.RX_CHARACTERISTIC_UUID) return
                writeInProgress = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    GeoRescueBleDiagnostics.verbose(
                        GeoRescueBleDiagnostics.WRITE_SUCCESS,
                        "role=CLIENT address=$deviceAddress queuedChunks=${writeQueue.size}"
                    )
                } else {
                    GeoRescueBleDiagnostics.error(
                        GeoRescueBleDiagnostics.WRITE_FAILED,
                        "role=CLIENT address=$deviceAddress status=$status"
                    )
                }
                pumpWrites()
            }

            // API 33+ notification callback (platform calls exactly one of the two forms).
            override fun onCharacteristicChanged(
                gattIn: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    handleIncomingNotification(characteristic, value)
                }
            }

            // Pre-API-33 notification callback.
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gattIn: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value
                    handleIncomingNotification(characteristic, value)
                }
            }
        }

        val started = try {
            remoteDevice.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.CONNECTION_FAILED,
                "role=CLIENT address=$deviceAddress code=SECURITY_EXCEPTION detail=${e.message}"
            )
            null
        } catch (e: IllegalArgumentException) {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.CONNECTION_FAILED,
                "role=CLIENT address=$deviceAddress code=INVALID_ADDRESS detail=${e.message}"
            )
            null
        }
        gatt = started
        return started != null
    }

    /**
     * Queues a packet frame for writing to the server's RX characteristic
     * (chunked to the negotiated MTU) and starts the write pump. Returns
     * true when the frame was accepted into the queue.
     */
    fun writeFrame(frameBytes: ByteArray): Boolean {
        if (closed) return false
        val state = stateMachine.state
        if (state != GeoRescueBleState.READY && state != GeoRescueBleState.TRANSFERRING) return false
        val gattLocal = gatt ?: return false
        val rx = remoteRx ?: return false
        if (frameBytes.isEmpty() || frameBytes.size > GeoRescueBlePacket.MAX_PACKET_BYTES) return false

        stateMachine.transitionTo(GeoRescueBleState.TRANSFERRING)
        val chunkSize = negotiatedMtu - ATT_HEADERS
        val chunks = GeoRescueBleFramer().chunksFor(frameBytes, chunkSize)
        synchronized(writeQueue) {
            writeQueue.addAll(chunks)
        }
        GeoRescueBleDiagnostics.info(
            GeoRescueBleDiagnostics.WRITE_STARTED,
            "role=CLIENT address=$deviceAddress chunks=${chunks.size} totalBytes=${frameBytes.size}"
        )
        pumpWrites()
        return true
    }

    private fun pumpWrites() {
        val gattLocal = gatt ?: return
        val rx = remoteRx ?: return
        if (writeInProgress) return
        val next: ByteArray? = synchronized(writeQueue) { writeQueue.removeFirstOrNull() }
        if (next == null) {
            // Queue drained: back to READY.
            stateMachine.transitionTo(GeoRescueBleState.READY)
            return
        }
        writeInProgress = true
        try {
            // ALWAYS write-with-response: several OEM stacks never invoke
            // onCharacteristicWrite for WRITE_TYPE_NO_RESPONSE, which jams
            // the chunk queue after the first write (the peer then receives
            // an unterminated frame and silently drops the packet). The
            // with-response path guarantees one callback per chunk, keeping
            // the pump flowing and the frame complete on the receiver.
            val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val initiated: Boolean =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattLocal.writeCharacteristic(rx, next, writeType) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    rx.value = next
                    @Suppress("DEPRECATION")
                    rx.writeType = writeType
                    @Suppress("DEPRECATION")
                    gattLocal.writeCharacteristic(rx)
                }
            if (!initiated) {
                writeInProgress = false
                GeoRescueBleDiagnostics.error(
                    GeoRescueBleDiagnostics.WRITE_FAILED,
                    "role=CLIENT address=$deviceAddress code=WRITE_REJECTED"
                )
            }
        } catch (e: Exception) {
            writeInProgress = false
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.WRITE_FAILED,
                "role=CLIENT address=$deviceAddress code=WRITE_EXCEPTION detail=${e.message}"
            )
        }
    }

    private fun handleIncomingNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != GeoRescueBleProfile.TX_CHARACTERISTIC_UUID) return
        val frame = framer.feed(value)
        if (frame != null) {
            GeoRescueBleDiagnostics.info(
                GeoRescueBleDiagnostics.DATA_RECEIVED,
                "role=CLIENT address=$deviceAddress frameChars=${frame.length}"
            )
            listener.onBytesReceived(this@GeoRescueBleConnection, frame.toByteArray(Charsets.UTF_8))
        }
    }

    /** Disconnects and releases the GATT client (safe to call repeatedly). */
    fun disconnect() {
        stateMachine.transitionTo(GeoRescueBleState.DISCONNECTING)
        closed = true
        try {
            gatt?.disconnect()
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.warn(
                GeoRescueBleDiagnostics.DISCONNECTED,
                "role=CLIENT address=$deviceAddress code=DISCONNECT_EXCEPTION detail=${e.message}"
            )
        }
    }

    private fun onMtuSettled(gattIn: BluetoothGatt, mtu: Int) {
        stateMachine.transitionTo(GeoRescueBleState.DISCOVERING_SERVICES)
        GeoRescueBleDiagnostics.info(
            GeoRescueBleDiagnostics.SERVICE_DISCOVERY_STARTED,
            "address=$deviceAddress"
        )
        gattIn.discoverServices()
    }

    private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean =
        try {
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        } catch (e: Exception) {
            false
        }

    private fun closeGattQuietly() {
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    fun currentState(): GeoRescueBleState = stateMachine.state

    private companion object {
        const val REQUESTED_MTU = 247
        const val DEFAULT_MTU = 23
        const val ATT_HEADERS = 3
        const val ERROR_NO_SERVICE = -1001
        const val ERROR_NO_CHARACTERISTICS = -1002
        const val ERROR_NO_CCCD = -1003
        const val ERROR_CCCD_WRITE_FAILED = -1004
    }
}

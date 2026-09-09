package com.example.georescux.domain.ble

/**
 * Single, recognizable diagnostic logging facade for the whole BLE
 * subsystem. Every BLE operation logs through this object with the tag
 * [LOG_TAG] ("GeoRescueX-BLE") so a two-device field test is fully
 * traceable from logcat alone.
 *
 * The Android application installs a sink that writes to android.util.Log
 * (see GeoRescueBleLogSink). Pure unit tests run sink-less without any
 * Android dependency, and the bounded in-memory event buffer doubles as
 * the on-screen log feed of the BLE diagnostics screen.
 */
object GeoRescueBleDiagnostics {

    const val LOG_TAG = "GeoRescueX-BLE"

    // Log level constants (mirror android.util.Log values; no Android import).
    const val LEVEL_VERBOSE = 2
    const val LEVEL_INFO = 4
    const val LEVEL_WARN = 5
    const val LEVEL_ERROR = 6

    /** Structural listener for the diagnostics screen. */
    interface BleLogListener {
        fun onBleEvent(event: GeoRescueBleEvent)
    }

    data class GeoRescueBleEvent(
        val timestampMs: Long,
        val level: Int,
        val eventName: String,
        val detail: String,
    ) {
        fun formatted(): String = "$eventName $detail"
    }

    // --- Named events (the diagnostic contract of the BLE subsystem) ---
    const val BLE_INIT_START = "BLE_INIT_START"
    const val BLE_INIT_SUCCESS = "BLE_INIT_SUCCESS"
    const val BLE_INIT_FAILED = "BLE_INIT_FAILED"
    const val BLE_INITIALIZED = "BLE_INITIALIZED"
    const val BLE_PERMISSION_MISSING = "BLE_PERMISSION_MISSING"
    const val PERMISSION_CHECK = "PERMISSION_CHECK"
    const val BLUETOOTH_DISABLED = "BLUETOOTH_DISABLED"
    const val BLUETOOTH_UNAVAILABLE = "BLUETOOTH_UNAVAILABLE"
    const val SCANNER_CREATED = "SCANNER_CREATED"
    const val SCAN_STARTED = "SCAN_STARTED"
    const val SCANNING_STARTED = "SCANNING_STARTED"
    const val SCAN_RESULT = "SCAN_RESULT"
    const val SCAN_STOPPED = "SCAN_STOPPED"
    const val SCAN_FAILED = "SCAN_FAILED"
    const val SCANNING_FAILED = "SCANNING_FAILED"
    const val DEVICE_FOUND = "DEVICE_FOUND"
    const val GEORESCUE_DEVICE_FOUND = "GEORESCUE_DEVICE_FOUND"
    const val PEER_FOUND = "PEER_FOUND"
    const val ADVERTISING_STARTED = "ADVERTISING_STARTED"
    const val ADVERTISING_STOPPED = "ADVERTISING_STOPPED"
    const val ADVERTISING_FAILED = "ADVERTISING_FAILED"
    const val CONNECTION_STARTED = "CONNECTION_STARTED"
    const val CONNECTION_SUCCESS = "CONNECTION_SUCCESS"
    const val CONNECTION_FAILED = "CONNECTION_FAILED"
    const val GATT_CONNECTING = "GATT_CONNECTING"
    const val GATT_CONNECTED = "GATT_CONNECTED"
    const val GATT_DISCONNECTED = "GATT_DISCONNECTED"
    const val GATT_READY = "GATT_READY"
    const val SERVICE_DISCOVERY_STARTED = "SERVICE_DISCOVERY_STARTED"
    const val SERVICE_DISCOVERY_SUCCESS = "SERVICE_DISCOVERY_SUCCESS"
    const val SERVICE_DISCOVERY_FAILED = "SERVICE_DISCOVERY_FAILED"
    const val GEORESCUE_SERVICE_FOUND = "GEORESCUE_SERVICE_FOUND"
    const val RX_CHARACTERISTIC_FOUND = "RX_CHARACTERISTIC_FOUND"
    const val TX_CHARACTERISTIC_FOUND = "TX_CHARACTERISTIC_FOUND"
    const val NOTIFICATION_ENABLED = "NOTIFICATION_ENABLED"
    const val NOTIFICATION_FAILED = "NOTIFICATION_FAILED"
    const val WRITE_STARTED = "WRITE_STARTED"
    const val WRITE_SUCCESS = "WRITE_SUCCESS"
    const val WRITE_FAILED = "WRITE_FAILED"
    const val DATA_RECEIVED = "DATA_RECEIVED"
    const val PACKET_RECEIVED = "PACKET_RECEIVED"
    const val PACKET_SENT = "PACKET_SENT"
    const val PACKET_VALID = "PACKET_VALID"
    const val PACKET_INVALID = "PACKET_INVALID"
    const val PACKET_DUPLICATE = "PACKET_DUPLICATE"
    const val PACKET_ACCEPTED = "PACKET_ACCEPTED"
    const val PACKET_STORED = "PACKET_STORED"
    const val PACKET_PERSISTED = "PACKET_PERSISTED"
    const val PACKET_RELAYED = "PACKET_RELAYED"
    const val PACKET_QUEUED = "PACKET_QUEUED"
    const val PACKET_TTL_EXPIRED = "PACKET_TTL_EXPIRED"
    const val PACKET_SYNCED_TO_FIREBASE = "PACKET_SYNCED_TO_FIREBASE"
    const val MTU_NEGOTIATED = "MTU_NEGOTIATED"
    const val DISCONNECTED = "DISCONNECTED"
    const val BLE_ERROR = "BLE_ERROR"
    const val SOS_PACKET_ENQUEUED = "SOS_PACKET_ENQUEUED"

    /** Where log lines go. Null in pure unit tests (and by default). */
    @Volatile
    var sink: ((Int, String, String) -> Unit)? = null

    /** Structural listeners (diagnostics screen, tests). */
    private val listeners = mutableListOf<BleLogListener>()

    /** Bounded event buffer shown on the diagnostics screen (latest last). */
    private val events = ArrayDeque<GeoRescueBleEvent>()

    private const val MAX_EVENTS = 250

    fun info(event: String, detail: String = "") = emit(LEVEL_INFO, event, detail)

    fun warn(event: String, detail: String = "") = emit(LEVEL_WARN, event, detail)

    fun error(event: String, detail: String = "") = emit(LEVEL_ERROR, event, detail)

    fun verbose(event: String, detail: String = "") = emit(LEVEL_VERBOSE, event, detail)

    private fun emit(level: Int, event: String, detail: String) {
        val entry = GeoRescueBleEvent(System.currentTimeMillis(), level, event, detail)
        synchronized(events) {
            events.addLast(entry)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        sink?.invoke(level, LOG_TAG, "${entry.eventName} ${entry.detail}")
        synchronized(listeners) {
            listeners.toList().forEach { listener ->
                try {
                    listener.onBleEvent(entry)
                } catch (_: Exception) {
                    // A faulty UI listener must never break BLE logging.
                }
            }
        }
    }

    fun addListener(listener: BleLogListener) = synchronized(listeners) { listeners.add(listener) }

    fun removeListener(listener: BleLogListener) = synchronized(listeners) { listeners.remove(listener) }

    /** Snapshot of the most recent events (oldest first). */
    fun recentEvents(): List<GeoRescueBleEvent> = synchronized(events) { events.toList() }
}

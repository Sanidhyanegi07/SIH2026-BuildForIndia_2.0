package com.example.georescux.data.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.example.georescux.domain.ble.GeoRescueBleDiagnostics

/**
 * Phase 4 — BLE scanning for nearby GeoRescuX nodes.
 *
 * The scan is filtered by the GeoRescuX service UUID, so DEVICE_FOUND is
 * the raw callback and GEORESCUE_DEVICE_FOUND marks a confirmed GeoRescuX
 * node. Diagnostic data reported per result: address (where Android
 * provides it), name (only when the remote advertises one), RSSI,
 * advertised services, timestamp.
 */
@Suppress("MissingPermission")
class GeoRescueBleScanner(private val adapter: BluetoothAdapter) {

    interface ScanListener {
        /** A confirmed GeoRescuX node was seen in the air. */
        fun onGeoRescueDeviceFound(device: BluetoothDevice, rssi: Int, advertisedServices: List<ParcelUuid>)

        fun onScanFailed(errorCode: Int)
    }

    private val scanner get() = adapter.bluetoothLeScanner
    private var activeCallback: ScanCallback? = null

    val isScanning: Boolean
        get() = activeCallback != null

    /** True when the adapter exposes a LE scanner (adapter must be on). */
    fun isSupported(): Boolean = adapter.bluetoothLeScanner != null

    /**
     * Starts a low-latency, hardware-filtered scan for the GeoRescuX
     * service UUID. Returns false synchronously on missing scanner or
     * SecurityException; asynchronous failures arrive via onScanFailed.
     */
    fun startScan(listener: ScanListener): Boolean {
        if (activeCallback != null) return true // already scanning
        val leScanner = scanner ?: run {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.SCAN_FAILED,
                "code=SCANNER_UNAVAILABLE"
            )
            return false
        }
        // Two ANY-match filters: the 4-byte manufacturer marker (reliable on
        // every OEM stack) plus the 128-bit service UUID (primary identity
        // where the stack supports it).
        val markerFilter = ScanFilter.Builder()
            .setManufacturerData(
                GeoRescueBleProfile.MANUFACTURER_ID,
                GeoRescueBleProfile.nodeMarkerBytes()
            )
            .build()
        val uuidFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(GeoRescueBleProfile.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val newCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = try {
                    result.device.address
                } catch (_: SecurityException) {
                    "UNKNOWN_ADDRESS"
                }
                val name = try {
                    result.device.name // often null or non-personal; only echoed, never stored
                } catch (_: SecurityException) {
                    null
                }
                val services = result.scanRecord?.serviceUuids?.toList() ?: emptyList()
                val markerData = result.scanRecord?.getManufacturerSpecificData(GeoRescueBleProfile.MANUFACTURER_ID)
                val isGeoRescuX = GeoRescueBleProfile.isGeoRescuXManufacturerData(
                    GeoRescueBleProfile.MANUFACTURER_ID, markerData
                ) || services.any { it.uuid == GeoRescueBleProfile.SERVICE_UUID }
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.DEVICE_FOUND,
                    "address=$address name=${name ?: "null"} rssi=${result.rssi} services=${services.size} marker=${markerData != null}"
                )
                if (isGeoRescuX) {
                    GeoRescueBleDiagnostics.info(
                        GeoRescueBleDiagnostics.GEORESCUE_DEVICE_FOUND,
                        "address=$address rssi=${result.rssi} via=${if (markerData != null) "marker" else "uuid"}"
                    )
                    listener.onGeoRescueDeviceFound(result.device, result.rssi, services)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                activeCallback = null
                GeoRescueBleDiagnostics.error(
                    GeoRescueBleDiagnostics.SCAN_FAILED,
                    "errorCode=$errorCode (${describeError(errorCode)})"
                )
                listener.onScanFailed(errorCode)
            }
        }
        return try {
            leScanner.startScan(listOf(markerFilter, uuidFilter), settings, newCallback)
            activeCallback = newCallback
            GeoRescueBleDiagnostics.info(
                GeoRescueBleDiagnostics.SCAN_STARTED,
                "filter=${GeoRescueBleProfile.SERVICE_UUID} mode=LOW_LATENCY"
            )
            true
        } catch (e: SecurityException) {
            activeCallback = null
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.SCAN_FAILED,
                "code=SECURITY_EXCEPTION detail=${e.message}"
            )
            false
        } catch (e: IllegalStateException) {
            activeCallback = null
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.SCAN_FAILED,
                "code=ADAPTER_OFF detail=${e.message}"
            )
            false
        }
    }

    fun stopScan() {
        val current = activeCallback ?: return
        try {
            scanner?.stopScan(current)
            GeoRescueBleDiagnostics.info(GeoRescueBleDiagnostics.SCAN_STOPPED)
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.warn(GeoRescueBleDiagnostics.SCAN_STOPPED, "detail=${e.message}")
        }
        activeCallback = null
    }

    private fun describeError(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APPLICATION_REGISTRATION_FAILED"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "OUT_OF_HARDWARE_RESOURCES"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "SCANNING_TOO_FREQUENTLY"
        else -> "UNKNOWN"
    }
}

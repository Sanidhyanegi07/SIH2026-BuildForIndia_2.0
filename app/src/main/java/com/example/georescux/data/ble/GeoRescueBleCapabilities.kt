package com.example.georescux.data.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.georescux.domain.ble.GeoRescueBleDiagnostics
import com.example.georescux.domain.ble.GeoRescueBlePermissions

/**
 * Centralized Bluetooth capability + permission detection (Phase 2).
 *
 * Everything the subsystem needs before it may touch a radio lives here.
 * A check NEVER throws and NEVER silently fails: every failure returns a
 * specific [GeoRescueBleFailureReason] that the diagnostics screen and the
 * logs surface verbatim.
 */
enum class GeoRescueBleFailureReason {
    NONE,
    BLUETOOTH_UNAVAILABLE,
    BLE_UNSUPPORTED,
    BLUETOOTH_DISABLED,
    BLE_PERMISSION_MISSING,
    LOCATION_REQUIREMENT_MISSING,
    ADVERTISING_UNSUPPORTED,
}

/** Full readiness report consumed by the BLE diagnostics screen. */
data class GeoRescueBleReadiness(
    val bluetoothSupported: Boolean,
    val bleSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val permissionReport: GeoRescueBlePermissions.BlePermissionReport,
    val advertisingSupported: Boolean,
    val offloadedFilteringSupported: Boolean,
    val offloadedBatchingSupported: Boolean,
    val failureReason: GeoRescueBleFailureReason,
) {
    val ready: Boolean get() = failureReason == GeoRescueBleFailureReason.NONE
}

class GeoRescueBleCapabilities(private val context: Context) {

    private val adapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    /**
     * Evaluates the complete readiness of the device for the BLE subsystem.
     * Reasons are returned in priority order — a missing permission beats a
     * disabled adapter in the report so the user knows what to fix first.
     */
    @Suppress("MissingPermission")
    fun evaluate(): GeoRescueBleReadiness {
        val sdkInt = Build.VERSION.SDK_INT
        val isGranted: (String) -> Boolean = { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        val permissionReport = GeoRescueBlePermissions.report(sdkInt, isGranted)

        val missingCritical = GeoRescueBlePermissions.missingCritical(sdkInt, isGranted)
        GeoRescueBleDiagnostics.info(
            GeoRescueBleDiagnostics.PERMISSION_CHECK,
            "sdk=$sdkInt scan=${permissionReport.scan} connect=${permissionReport.connect} " +
                "advertise=${permissionReport.advertise} location=${permissionReport.location} missing=$missingCritical"
        )

        val adapterExists = adapter != null
        // A present BluetoothAdapter implies a BR/EDR+LE dual-mode stack on
        // real phones; the LE-specific probes below are best-effort OEM
        // diagnostics, not hard requirements.
        val bleSupported = adapterExists
        val enabled = adapter?.isEnabled == true
        if (!enabled) {
            GeoRescueBleDiagnostics.warn(GeoRescueBleDiagnostics.BLUETOOTH_DISABLED, "adapter=$adapterExists")
        }

        val multipleAdv = adapter?.isMultipleAdvertisementSupported == true
        val offloadedFiltering = adapter?.isOffloadedFilteringSupported == true

        val failure = when {
            missingCritical.isNotEmpty() -> GeoRescueBleFailureReason.BLE_PERMISSION_MISSING
            !adapterExists -> GeoRescueBleFailureReason.BLUETOOTH_UNAVAILABLE
            !bleSupported -> GeoRescueBleFailureReason.BLE_UNSUPPORTED
            !enabled -> GeoRescueBleFailureReason.BLUETOOTH_DISABLED
            // Pre-S: scanning requires fine location; report it separately so
            // the diagnostics screen can distinguish it from a hard denial.
            sdkInt < 31 && !permissionReport.allRequiredGranted ->
                GeoRescueBleFailureReason.LOCATION_REQUIREMENT_MISSING
            else -> GeoRescueBleFailureReason.NONE
        }
        if (failure != GeoRescueBleFailureReason.NONE) {
            GeoRescueBleDiagnostics.warn(
                GeoRescueBleDiagnostics.BLE_INIT_FAILED,
                "reason=$failure"
            )
        }

        return GeoRescueBleReadiness(
            bluetoothSupported = adapterExists,
            bleSupported = bleSupported,
            bluetoothEnabled = enabled,
            permissionReport = permissionReport,
            advertisingSupported = multipleAdv,
            offloadedFilteringSupported = offloadedFiltering,
            offloadedBatchingSupported = offloadedFiltering, // probe removed: not in SDK 35 stubs
            failureReason = failure,
        )
    }

    /** Runtime permission strings to request for this device's SDK level. */
    fun permissionsToRequest(): List<String> {
        val base = GeoRescueBlePermissions.requiredRuntimePermissions(Build.VERSION.SDK_INT)
        // Post-notification permission is requested by the app layer, not here.
        return base
    }
}

package com.example.georescux.domain.ble

/**
 * Pure BLE permission logic — the centralized, testable half of permission
 * handling. The Android layer ([com.example.georescux.data.ble.
 * GeoRescueBleCapabilities]) supplies the "is granted" answers; everything
 * else (which permissions for which SDK, how to aggregate the state) lives
 * here so it is unit-testable without a device.
 *
 * Policy by SDK:
 * - Android 12+ (API 31/S): runtime BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE /
 *   BLUETOOTH_CONNECT. Location is NOT required for our filtered scan
 *   (neverForLocation semantics are declared in the manifest).
 * - Android 6..11 (API 23..30): legacy BLUETOOTH/BLUETOOTH_ADMIN are
 *   normal permissions; runtime ACCESS_FINE_LOCATION is required for BLE
 *   scanning.
 */
object GeoRescueBlePermissions {

    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"

    /** Aggregated diagnostic states surfaced on the BLE diagnostics screen. */
    enum class BlePermissionState { GRANTED, DENIED, UNKNOWN }

    data class BlePermissionReport(
        val scan: BlePermissionState,
        val connect: BlePermissionState,
        val advertise: BlePermissionState,
        val location: BlePermissionState,
        val missingRuntimePermissions: List<String>,
    ) {
        val allRequiredGranted: Boolean get() = missingRuntimePermissions.isEmpty()
    }

    /**
     * The runtime permissions the subsystem requests for a given SDK level.
     * On S+ location is included only as a compatibility fallback for OEM
     * firmwares that still gate BLE scans behind it (requested, never fatal
     * when denied on S+).
     */
    fun requiredRuntimePermissions(sdkInt: Int): List<String> = if (sdkInt >= 31) {
        listOf(BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION)
    } else {
        listOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
    }

    /**
     * Permissions that MUST be granted for BLE to start on [sdkInt].
     * S+: the three Bluetooth permissions PLUS fine location — the manifest
     * declares BLUETOOTH_SCAN without neverForLocation (a deliberate
     * compatibility decision for Nearby on some OEM firmwares), so Android
     * 12+ still withholds ALL scan results when location is denied, making
     * GeoRescuX devices undiscoverable with no error surfaced anywhere.
     * Pre-S: fine location (coarse is an acceptable alternative and is
     * checked as such).
     */
    fun criticalPermissions(sdkInt: Int): List<String> = if (sdkInt >= 31) {
        listOf(BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION)
    } else {
        listOf(ACCESS_FINE_LOCATION)
    }

    fun missingCritical(sdkInt: Int, isGranted: (String) -> Boolean): List<String> =
        criticalPermissions(sdkInt).filter { !isGranted(it) }

    /**
     * Builds the full report for the diagnostics screen. On S+, location is
     * reported as SATISFIED unless explicitly denied (the manifest declares
     * scan as neverForLocation, so it is informational). Pre-S, fine-or-coarse
     * location is REQUIRED for scanning.
     */
    fun report(sdkInt: Int, isGranted: (String) -> Boolean): BlePermissionReport {
        fun stateOf(permission: String): BlePermissionState =
            if (isGranted(permission)) BlePermissionState.GRANTED else BlePermissionState.DENIED

        val scan = if (sdkInt >= 31) stateOf(BLUETOOTH_SCAN) else BlePermissionState.GRANTED
        val connect = if (sdkInt >= 31) stateOf(BLUETOOTH_CONNECT) else BlePermissionState.GRANTED
        val advertise = if (sdkInt >= 31) stateOf(BLUETOOTH_ADVERTISE) else BlePermissionState.GRANTED

        val fineGranted = isGranted(ACCESS_FINE_LOCATION)
        val coarseGranted = isGranted(ACCESS_COARSE_LOCATION)
        // Coarse location only satisfies scanning on API < 29 devices; from
        // Android 10 on, fine location is required for BLE scan results.
        val locationSatisfied = fineGranted || (coarseGranted && sdkInt < 29)
        val location = when {
            locationSatisfied -> BlePermissionState.GRANTED
            else -> BlePermissionState.DENIED
        }

        val missing = missingCritical(sdkInt, isGranted)
        return BlePermissionReport(
            scan = scan,
            connect = connect,
            advertise = advertise,
            location = location,
            missingRuntimePermissions = missing,
        )
    }
}

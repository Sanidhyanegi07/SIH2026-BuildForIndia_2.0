package com.example.georescux.data.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import com.example.georescux.domain.ble.GeoRescueBleDiagnostics

/**
 * Phase 3 — BLE advertising for GeoRescuX.
 *
 * The advertisement identifies this device as a GeoRescuX emergency node
 * by the service UUID alone. It deliberately contains NO device name, NO
 * manufacturer data and NO payload: advertising is discovery only, and
 * device names frequently contain the owner's real identity.
 *
 * Uses the (deprecated but fully supported) AdvertiseCallback API — the
 * modern AdvertisingSet API adds complexity this subsystem does not need.
 */
@Suppress("DEPRECATION")
class GeoRescueBleAdvertiser(adapter: BluetoothAdapter) {

    interface AdvertisingListener {
        fun onAdvertisingStarted()

        fun onAdvertisingFailed(errorCode: Int)
    }

    private val advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = adapter.bluetoothLeAdvertiser
    private var callback: AdvertiseCallback? = null

    val isAdvertising: Boolean
        get() = callback != null

    /** True when this adapter can advertise at all (best-effort OEM probe). */
    fun isSupported(): Boolean = advertiser != null

    /**
     * Starts connectable advertising carrying the GeoRescuX service UUID.
     * Connectable is REQUIRED: the scanning side initiates the GATT
     * connection against this advertisement; a non-connectable
     * advertisement would make the device permanently undiscoverable for
     * connection.
     *
     * Returns false synchronously when advertising is unsupported or a
     * SecurityException fires (missing BLUETOOTH_ADVERTISE) — the caller
     * reports the diagnostic state instead of crashing.
     */
    fun startAdvertising(listener: AdvertisingListener): Boolean {
        if (callback != null) return true // already advertising
        val leAdvertiser = advertiser ?: run {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.ADVERTISING_FAILED,
                "code=ADVERTISER_UNAVAILABLE"
            )
            return false
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(GeoRescueBleProfile.SERVICE_UUID))
            // No device name, no service data, no manufacturer data —
            // discovery must not leak personal information.
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val newCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.ADVERTISING_STARTED,
                    "mode=${settingsInEffect?.mode} txPower=${settingsInEffect?.txPowerLevel} connectable=${settingsInEffect?.isConnectable}"
                )
                listener.onAdvertisingStarted()
            }

            override fun onStartFailure(errorCode: Int) {
                callback = null
                GeoRescueBleDiagnostics.error(
                    GeoRescueBleDiagnostics.ADVERTISING_FAILED,
                    "errorCode=$errorCode (${describeError(errorCode)})"
                )
                listener.onAdvertisingFailed(errorCode)
            }
        }
        return try {
            leAdvertiser.startAdvertising(settings, data, newCallback)
            callback = newCallback
            true
        } catch (e: SecurityException) {
            callback = null
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.ADVERTISING_FAILED,
                "code=SECURITY_EXCEPTION detail=${e.message}"
            )
            false
        } catch (e: IllegalStateException) {
            callback = null
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.ADVERTISING_FAILED,
                "code=ILLEGAL_STATE detail=${e.message}"
            )
            false
        }
    }

    fun stopAdvertising() {
        val current = callback ?: return
        try {
            advertiser?.stopAdvertising(current)
            GeoRescueBleDiagnostics.info(GeoRescueBleDiagnostics.ADVERTISING_STOPPED)
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.warn(
                GeoRescueBleDiagnostics.ADVERTISING_STOPPED,
                "detail=${e.message}"
            )
        }
        callback = null
    }

    private fun describeError(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        else -> "UNKNOWN"
    }
}

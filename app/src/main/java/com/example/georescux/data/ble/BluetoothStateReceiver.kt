package com.example.georescux.data.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.example.georescux.domain.ble.GeoRescueBleDiagnostics

/**
 * Monitors Bluetooth adapter state changes (on/off).
 *
 * Registered dynamically by [GeoRescueBleForegroundService] to safely pause
 * operations when the user toggles Bluetooth OFF and automatically recover
 * (re-evaluating capabilities, restarting advertising, and resuming the scan cycle)
 * when Bluetooth is switched back ON.
 */
class BluetoothStateReceiver(
    private val onBluetoothEnabled: () -> Unit,
    private val onBluetoothDisabled: () -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        when (state) {
            BluetoothAdapter.STATE_ON -> {
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.BLE_INIT_START,
                    "Bluetooth adapter turned ON - recovering BLE mesh"
                )
                onBluetoothEnabled()
            }
            BluetoothAdapter.STATE_TURNING_OFF,
            BluetoothAdapter.STATE_OFF -> {
                GeoRescueBleDiagnostics.warn(
                    GeoRescueBleDiagnostics.BLUETOOTH_DISABLED,
                    "Bluetooth adapter turned OFF - pausing BLE mesh"
                )
                onBluetoothDisabled()
            }
        }
    }

    companion object {
        fun createIntentFilter(): IntentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    }
}

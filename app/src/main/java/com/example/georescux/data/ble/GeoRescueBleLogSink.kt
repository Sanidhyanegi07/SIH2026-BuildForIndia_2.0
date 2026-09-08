package com.example.georescux.data.ble

import android.util.Log
import com.example.georescux.domain.ble.GeoRescueBleDiagnostics

/**
 * Installs the android.util.Log sink for [GeoRescueBleDiagnostics] so every
 * BLE diagnostic event reaches logcat under the single tag
 * "GeoRescueX-BLE". Called once from AppContainer initialization.
 */
object GeoRescueBleLogSink {

    @Volatile
    private var installed = false

    fun installOnce() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            GeoRescueBleDiagnostics.sink = { level, tag, message ->
                when (level) {
                    GeoRescueBleDiagnostics.LEVEL_VERBOSE -> Log.v(tag, message)
                    GeoRescueBleDiagnostics.LEVEL_INFO -> Log.i(tag, message)
                    GeoRescueBleDiagnostics.LEVEL_WARN -> Log.w(tag, message)
                    GeoRescueBleDiagnostics.LEVEL_ERROR -> Log.e(tag, message)
                    else -> Log.i(tag, message)
                }
            }
            installed = true
        }
    }
}

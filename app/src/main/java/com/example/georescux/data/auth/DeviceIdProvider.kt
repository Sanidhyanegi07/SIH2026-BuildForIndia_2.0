package com.example.georescux.data.auth

import android.content.Context
import java.util.UUID

/**
 * Provides a stable, per-installation device identifier that is used as [RelayEngine.selfOriginId]
 * when no Firebase user is signed in.
 *
 * Without this, every offline device falls back to the hardcoded string "DEVICE_LOCAL". When two
 * devices both use "DEVICE_LOCAL" as their originId, Device B's RelayEngine returns
 * [RelayDecision.OWN_EVENT] for every SOS sent by Device A — silently dropping all alerts.
 *
 * The ID is a random UUID generated once on first launch and persisted in SharedPreferences.
 * It survives process death and app upgrades, but is cleared on app data reset (intentional —
 * a data-reset device is effectively a new installation).
 */
object DeviceIdProvider {

    private const val PREFS_NAME = "georescux_device_prefs"
    private const val KEY_DEVICE_ID = "device_installation_id"

    fun getOrCreate(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = "DEVICE_${UUID.randomUUID()}"
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }
}

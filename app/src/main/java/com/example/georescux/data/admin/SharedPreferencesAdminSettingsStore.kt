package com.example.georescux.data.admin

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed admin settings store.
 * Persists admin portal configuration across app restarts.
 */
interface AdminSettingsStore {
    var syncIntervalMinutes: Int
    var dataRetentionDays: Int
    var alertTTLDays: Int
}

/**
 * SharedPreferences-backed admin settings store.
 * Persists admin portal configuration across app restarts.
 */
class SharedPreferencesAdminSettingsStore(context: Context) : AdminSettingsStore {

    private val prefs = context.getSharedPreferences("admin_settings", Context.MODE_PRIVATE)

    override var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL)
        set(value) { prefs.edit().putInt(KEY_SYNC_INTERVAL, value).apply() }

    override var dataRetentionDays: Int
        get() = prefs.getInt(KEY_DATA_RETENTION, DEFAULT_DATA_RETENTION)
        set(value) { prefs.edit().putInt(KEY_DATA_RETENTION, value).apply() }

    override var alertTTLDays: Int
        get() = prefs.getInt(KEY_ALERT_TTL, DEFAULT_ALERT_TTL)
        set(value) { prefs.edit().putInt(KEY_ALERT_TTL, value).apply() }

    companion object {
        const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
        const val KEY_DATA_RETENTION = "data_retention_days"
        const val KEY_ALERT_TTL = "alert_ttl_days"

        const val DEFAULT_SYNC_INTERVAL = 30
        const val DEFAULT_DATA_RETENTION = 90
        const val DEFAULT_ALERT_TTL = 7
    }
}
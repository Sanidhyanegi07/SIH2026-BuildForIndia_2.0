package com.example.georescux.data.alerts

import android.content.Context
import com.example.georescux.domain.alerts.AdminAlert
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local persistence for administrative alerts (spec §20 requirement that
 * alerts "remain accessible offline after they have been stored locally").
 *
 * An alert is stored the moment it is delivered, so it stays readable with
 * or without a connection afterwards.
 */
class AdminAlertStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAll(): List<AdminAlert> {
        val array = JSONArray(prefs.getString(KEY_ALERTS, "[]"))
        return (0 until array.length()).mapNotNull { index ->
            runCatching { fromJson(array.getJSONObject(index)) }.getOrNull()
        }
    }

    /** Adds any alerts not already stored, keyed by id (deduplicated). */
    fun addAll(alerts: List<AdminAlert>) {
        if (alerts.isEmpty()) return
        val existing = loadAll().associateBy { it.id }.toMutableMap()
        var changed = false
        alerts.forEach { alert ->
            if (existing.put(alert.id, alert) == null) changed = true
        }
        if (!changed) return
        prefs.edit().putString(KEY_ALERTS, toJson(existing.values.toList()).toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ALERTS).apply()
    }

    private fun toJson(alerts: List<AdminAlert>): JSONArray = JSONArray().apply {
        alerts.forEach { alert ->
            put(JSONObject().apply {
                put("id", alert.id)
                put("title", alert.title)
                put("body", alert.body)
                put("timestampMs", alert.timestampMs)
                put("scopeType", alert.scopeType)
                put("regionId", alert.regionId ?: JSONObject.NULL)
                put("district", alert.district ?: JSONObject.NULL)
            })
        }
    }

    private fun fromJson(json: JSONObject): AdminAlert = AdminAlert(
        id = json.getString("id"),
        title = json.optString("title", ""),
        body = json.optString("body", ""),
        timestampMs = json.optLong("timestampMs", 0L),
        scopeType = json.optString("scopeType", AdminAlert.SCOPE_INDIA),
        regionId = json.optString("regionId", "").takeIf { it.isNotBlank() },
        district = json.optString("district", "").takeIf { it.isNotBlank() },
    )

    private companion object {
        const val PREFS = "admin_alerts"
        const val KEY_ALERTS = "alerts"
    }
}

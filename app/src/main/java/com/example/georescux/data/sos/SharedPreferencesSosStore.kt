package com.example.georescux.data.sos

import android.content.Context
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed SOS store — simple, reliable and fully offline.
 * Uses org.json, which is built into Android.
 */
class SharedPreferencesSosStore(context: Context) : LocalSosStore {

    private val prefs = context.getSharedPreferences("sos_store", Context.MODE_PRIVATE)

    override fun loadActive(): SosEmergency? {
        val json = prefs.getString(KEY_ACTIVE, null) ?: return null
        return fromJson(JSONObject(json))
    }

    override fun saveActive(emergency: SosEmergency) {
        prefs.edit().putString(KEY_ACTIVE, toJson(emergency).toString()).apply()
    }

    override fun clearActive() {
        prefs.edit().remove(KEY_ACTIVE).apply()
    }

    override fun loadHistory(): List<SosEmergency> {
        val array = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        return (0 until array.length())
            .map { fromJson(array.getJSONObject(it)) }
            .asReversed() // newest first
    }

    override fun addToHistory(emergency: SosEmergency) {
        val array = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        array.put(toJson(emergency))
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun toJson(emergency: SosEmergency): JSONObject = JSONObject().apply {
        put("id", emergency.id)
        put("startedAtMs", emergency.startedAtMs)
        put("stoppedAtMs", emergency.stoppedAtMs ?: JSONObject.NULL)
        put("location", emergency.location?.let { location ->
            JSONObject()
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("accuracyMeters", location.accuracyMeters.toDouble())
                .put("timestampMs", location.timestampMs)
                .put("provider", location.provider)
        } ?: JSONObject.NULL)
        put("locationStatus", emergency.locationStatus?.name ?: JSONObject.NULL)
    }

    private fun fromJson(json: JSONObject): SosEmergency {
        // opt* reads keep records saved by older app versions readable:
        // they simply have no location information.
        val location = json.optJSONObject("location")?.let { loc ->
            SosLocation(
                latitude = loc.getDouble("latitude"),
                longitude = loc.getDouble("longitude"),
                accuracyMeters = loc.optDouble("accuracyMeters", 0.0).toFloat(),
                timestampMs = loc.optLong("timestampMs", 0L),
                provider = loc.optString("provider", "unknown"),
            )
        }
        val locationStatus = json.optString("locationStatus", "")
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { SosLocationStatus.valueOf(it) }.getOrNull() }
        return SosEmergency(
            id = json.getString("id"),
            startedAtMs = json.getLong("startedAtMs"),
            stoppedAtMs = if (json.isNull("stoppedAtMs")) null else json.getLong("stoppedAtMs"),
            location = location,
            locationStatus = locationStatus,
        )
    }

    private companion object {
        const val KEY_ACTIVE = "active_emergency"
        const val KEY_HISTORY = "sos_history"
    }
}

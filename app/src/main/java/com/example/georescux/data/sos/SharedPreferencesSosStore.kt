package com.example.georescux.data.sos

import android.content.Context
import com.example.georescux.domain.sos.SosEmergency
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
        return SosEmergencyJson.fromJson(JSONObject(json))
    }

    override fun saveActive(emergency: SosEmergency) {
        prefs.edit().putString(KEY_ACTIVE, SosEmergencyJson.toJson(emergency).toString()).apply()
    }

    override fun clearActive() {
        prefs.edit().remove(KEY_ACTIVE).apply()
    }

    override fun loadHistory(): List<SosEmergency> {
        val array = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        return (0 until array.length())
            .map { SosEmergencyJson.fromJson(array.getJSONObject(it)) }
            .asReversed() // newest first
    }

    override fun addToHistory(emergency: SosEmergency) {
        val array = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        array.put(SosEmergencyJson.toJson(emergency))
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private companion object {
        const val KEY_ACTIVE = "active_emergency"
        const val KEY_HISTORY = "sos_history"
    }
}

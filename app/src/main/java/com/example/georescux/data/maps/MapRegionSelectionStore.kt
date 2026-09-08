package com.example.georescux.data.maps

import android.content.Context

/**
 * Persists the user's active map region id. Local-only (never synced,
 * never Firebase) — which regional routing graph the Safe Route screen
 * operates on is a device preference, following the same
 * SharedPreferences-store pattern as the routing store itself.
 */
class MapRegionSelectionStore(context: Context) {

    private val prefs = context.getSharedPreferences("map_region_store", Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY_REGION_ID, null)

    fun save(regionId: String) {
        prefs.edit().putString(KEY_REGION_ID, regionId).apply()
    }

    private companion object {
        const val KEY_REGION_ID = "active_region_id"
    }
}

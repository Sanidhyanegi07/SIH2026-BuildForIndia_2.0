package com.example.georescux.data.sos

import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus
import org.json.JSONObject

/**
 * Serialization of [SosEmergency] to and from JSON.
 *
 * Extracted from [SharedPreferencesSosStore] so the on-disk contract can be
 * unit-tested on the JVM without an Android Context. Every field persisted
 * here must be read back by [fromJson], otherwise it is silently dropped
 * between process death and the BLE/cloud bridges that re-read the record.
 */
internal object SosEmergencyJson {

    fun toJson(emergency: SosEmergency): JSONObject = JSONObject().apply {
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
        put("note", emergency.note?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
    }

    fun fromJson(json: JSONObject): SosEmergency {
        // opt* reads keep records saved by older app versions readable:
        // they simply have no location or note information.
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
            note = json.optString("note", "").takeIf { it.isNotBlank() },
        )
    }
}

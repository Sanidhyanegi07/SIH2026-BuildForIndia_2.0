package com.example.georescux.domain.sos

/**
 * Maps SOS emergencies to the plain map structure stored in Firebase
 * Realtime Database. Pure Kotlin — JVM testable, no Android APIs.
 *
 * Deterministic: the same emergency always produces the same payload.
 * Null values (stoppedAtMs, location, locationStatus) are preserved as
 * nulls — Realtime Database removes those fields, which is exactly the
 * meaning of an ACTIVE emergency without a location fix yet.
 */
object SosCloudPayload {

    private const val STATUS_ACTIVE = "ACTIVE"
    private const val STATUS_COMPLETED = "COMPLETED"

    /** sos_alerts/{uid}/{alertId} = { id, status, startedAtMs, stoppedAtMs, location, locationStatus } */
    fun fromEmergency(emergency: SosEmergency): Map<String, Any?> {
        val location = emergency.location?.let { loc ->
            mapOf(
                "latitude" to loc.latitude,
                "longitude" to loc.longitude,
                "accuracyMeters" to loc.accuracyMeters,
                "timestampMs" to loc.timestampMs,
                "provider" to loc.provider,
            )
        }
        return mapOf(
            "id" to emergency.id,
            "status" to if (emergency.stoppedAtMs == null) STATUS_ACTIVE else STATUS_COMPLETED,
            "startedAtMs" to emergency.startedAtMs,
            "stoppedAtMs" to emergency.stoppedAtMs,
            "location" to location,
            "locationStatus" to emergency.locationStatus?.name,
        )
    }

    /** Full history keyed by alert id: sos_alerts/{uid} = { alertId: {…}, … } */
    fun fromHistory(emergencies: List<SosEmergency>): Map<String, Map<String, Any?>> =
        emergencies.associate { it.id to fromEmergency(it) }
}

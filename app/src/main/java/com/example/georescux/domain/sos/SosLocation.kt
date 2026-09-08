package com.example.georescux.domain.sos

/** A single location fix attached to an SOS emergency. */
data class SosLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val provider: String,
)

/**
 * Where the SOS location currently stands.
 * Null means the app is still trying to get the first fix
 * ("Getting location...").
 */
enum class SosLocationStatus {
    ACQUIRED,
    PERMISSION_MISSING,
    UNAVAILABLE,
}

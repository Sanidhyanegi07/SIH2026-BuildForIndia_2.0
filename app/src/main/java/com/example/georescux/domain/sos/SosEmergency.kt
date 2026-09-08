package com.example.georescux.domain.sos

/**
 * One SOS emergency. A record with a null [stoppedAtMs] is the currently
 * active emergency; once stopped it becomes a history entry.
 *
 * [location] holds the best available fix (null until one arrives) and
 * [locationStatus] tracks how the location attempt is going (null while
 * the first fix is still being fetched).
 */
data class SosEmergency(
    val id: String,
    val startedAtMs: Long,
    val stoppedAtMs: Long? = null,
    val location: SosLocation? = null,
    val locationStatus: SosLocationStatus? = null,
) {
    val isActive: Boolean get() = stoppedAtMs == null
}

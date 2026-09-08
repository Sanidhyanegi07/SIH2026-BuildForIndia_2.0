package com.example.georescux.domain.sos

/**
 * Pure decision rules for SOS location updates (no Android code, unit
 * tested): the first fix persists immediately, later fixes are throttled
 * to about one per minute, and "unavailable" is only reported while the
 * app is still waiting for the very first fix.
 */
object SosLocationPolicy {
    const val THROTTLE_MS = 60_000L
    const val TIMEOUT_MS = 15_000L

    /** The first fix (lastPersistAtMs == 0) always persists; later ones are throttled. */
    fun shouldPersistFix(lastPersistAtMs: Long, nowMs: Long): Boolean =
        lastPersistAtMs == 0L || nowMs - lastPersistAtMs >= THROTTLE_MS

    /** "Unavailable" applies only when no status has been recorded yet. */
    fun shouldMarkUnavailable(currentStatus: SosLocationStatus?): Boolean =
        currentStatus == null
}

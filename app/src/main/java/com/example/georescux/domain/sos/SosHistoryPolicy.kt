package com.example.georescux.domain.sos

/**
 * Bounds the local SOS history so the SharedPreferences JSON store cannot
 * grow without limit (every read and every cloud catch-up re-parses it).
 * Keeps the NEWEST [cap] emergencies by start time, newest first.
 */
object SosHistoryPolicy {
    const val MAX_HISTORY = 50

    fun keepNewest(emergencies: List<SosEmergency>, cap: Int = MAX_HISTORY): List<SosEmergency> =
        emergencies.sortedByDescending { it.startedAtMs }.take(cap.coerceAtLeast(0))
}

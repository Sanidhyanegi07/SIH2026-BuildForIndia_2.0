package com.example.georescux.domain.routing

/**
 * Estimates travel time for an evacuation route (spec §13: "Estimated travel
 * time").
 *
 * Deliberately simple and honest about it: the road model carries distance
 * and hazard penalties but no per-segment speed limit or surface class, so
 * the ETA assumes one conservative mixed-mode evacuation pace rather than
 * pretending to know vehicle type, terrain or traffic. Treat the result as
 * a planning estimate, never as a precise arrival time.
 */
object EtaEstimator {

    /**
     * Assumed evacuation pace in metres per second (~18 km/h): a deliberate
     * blend of vehicle speeds on hill roads and walking pace, biased slow
     * because disaster conditions degrade travel.
     */
    const val ASSUMED_SPEED_MPS = 5.0

    /** Never report an ETA below this — a sub-minute estimate is noise. */
    private const val MIN_SECONDS = 60L

    fun estimateSeconds(
        distanceMeters: Double,
        speedMps: Double = ASSUMED_SPEED_MPS,
    ): Long {
        if (distanceMeters <= 0.0 || speedMps <= 0.0) return 0L
        return (distanceMeters / speedMps).toLong().coerceAtLeast(MIN_SECONDS)
    }

    /** Human-readable ETA: "18 min", "1 h 05 min", or "—" when unknown. */
    fun format(seconds: Long): String {
        if (seconds <= 0L) return "—"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours <= 0L -> "${minutes} min"
            else -> "${hours} h ${minutes.toString().padStart(2, '0')} min"
        }
    }

    /** Distance in kilometres with one decimal, e.g. "7.2 km". */
    fun formatDistance(distanceMeters: Double): String {
        if (distanceMeters <= 0.0) return "—"
        return "%.1f km".format(distanceMeters / 1000.0)
    }
}

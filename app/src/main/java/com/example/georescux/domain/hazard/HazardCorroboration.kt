package com.example.georescux.domain.hazard

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentType
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class CorroboratedHazardCluster(
    val latitude: Double,
    val longitude: Double,
    val reports: List<IncidentEvent>,
    val reportCount: Int,
    val isCorroborated: Boolean,
    val fromNodeId: String? = null,
    val toNodeId: String? = null,
)

/**
 * Pure evaluation logic for evidence-based hazard corroboration.
 *
 * Rules:
 * - A single unverified report is NEVER auto-trusted as a high-confidence hazard.
 * - Requires at least [minReportsRequired] (default 2) independent reports within
 *   [maxDistanceMeters] (default 200m) and [timeWindowMs] (default 1 hour).
 * - Direct ROUTE_BLOCK reports targeting explicit road nodes automatically provide node endpoints.
 */
class HazardCorroboration(
    val minReportsRequired: Int = DEFAULT_MIN_REPORTS,
    val maxDistanceMeters: Double = DEFAULT_MAX_DISTANCE_METERS,
    val timeWindowMs: Long = DEFAULT_TIME_WINDOW_MS,
) {

    fun evaluateLocation(
        events: List<IncidentEvent>,
        targetLat: Double,
        targetLng: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): CorroboratedHazardCluster {
        val validHazardEvents = events.filter { isEligibleHazard(it, nowMs) }

        val matchingReports = validHazardEvents.filter { event ->
            val lat = event.latitude ?: return@filter false
            val lng = event.longitude ?: return@filter false
            haversineMeters(targetLat, targetLng, lat, lng) <= maxDistanceMeters
        }

        val uniqueOrigins = matchingReports.map { it.originId }.distinct()
        val count = uniqueOrigins.size
        val corroborated = count >= minReportsRequired

        val firstWithNodes = matchingReports.firstOrNull {
            it.payload.containsKey("fromNodeId") && it.payload.containsKey("toNodeId")
        }

        return CorroboratedHazardCluster(
            latitude = targetLat,
            longitude = targetLng,
            reports = matchingReports,
            reportCount = count,
            isCorroborated = corroborated,
            fromNodeId = firstWithNodes?.payload?.get("fromNodeId"),
            toNodeId = firstWithNodes?.payload?.get("toNodeId"),
        )
    }

    fun findCorroboratedClusters(
        events: List<IncidentEvent>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<CorroboratedHazardCluster> {
        val eligible = events.filter { isEligibleHazard(it, nowMs) }
        val clusters = mutableListOf<CorroboratedHazardCluster>()
        val processedEventIds = mutableSetOf<String>()

        for (event in eligible) {
            if (processedEventIds.contains(event.eventId)) continue
            val lat = event.latitude ?: continue
            val lng = event.longitude ?: continue

            val clusterEvents = eligible.filter { other ->
                val oLat = other.latitude ?: return@filter false
                val oLng = other.longitude ?: return@filter false
                haversineMeters(lat, lng, oLat, oLng) <= maxDistanceMeters
            }

            clusterEvents.forEach { processedEventIds.add(it.eventId) }

            val uniqueOrigins = clusterEvents.map { it.originId }.distinct()
            val isCorroborated = uniqueOrigins.size >= minReportsRequired

            val firstWithNodes = clusterEvents.firstOrNull {
                it.payload.containsKey("fromNodeId") && it.payload.containsKey("toNodeId")
            }

            clusters.add(
                CorroboratedHazardCluster(
                    latitude = lat,
                    longitude = lng,
                    reports = clusterEvents,
                    reportCount = uniqueOrigins.size,
                    isCorroborated = isCorroborated,
                    fromNodeId = firstWithNodes?.payload?.get("fromNodeId"),
                    toNodeId = firstWithNodes?.payload?.get("toNodeId"),
                )
            )
        }

        return clusters
    }

    private fun isEligibleHazard(event: IncidentEvent, nowMs: Long): Boolean {
        if (event.type != IncidentType.HAZARD && event.type != IncidentType.ROUTE_BLOCK) return false
        if (event.latitude == null || event.longitude == null) return false
        val ageMs = nowMs - event.occurredAtMs
        if (ageMs < 0 || ageMs > timeWindowMs) return false
        return true
    }

    companion object {
        const val DEFAULT_MIN_REPORTS = 2
        const val DEFAULT_MAX_DISTANCE_METERS = 200.0
        const val DEFAULT_TIME_WINDOW_MS = 3_600_000L // 1 hour

        fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val earthRadiusMeters = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val halfChord = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
            return 2 * earthRadiusMeters * asin(sqrt(halfChord))
        }
    }
}

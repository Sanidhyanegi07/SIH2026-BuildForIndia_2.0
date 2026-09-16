package com.example.georescux.domain.routing

/**
 * The result of a route search: the ordered node ids from start to
 * destination (inclusive), the plain distance of the used roads, the
 * hazard-aware cost, the hazards on the roads actually used, and an
 * estimated travel time.
 */
data class EvacuationRoute(
    val nodeIds: List<String>,
    val totalDistanceMeters: Double,
    val totalCostMeters: Double,
    val hazardWarnings: List<RoadHazard> = emptyList(),
    val estimatedDurationSeconds: Long = 0L,
)

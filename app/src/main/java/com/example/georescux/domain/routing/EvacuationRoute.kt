package com.example.georescux.domain.routing

/**
 * The result of a route search: the ordered node ids from start to
 * destination (inclusive), the plain distance of the used roads, the
 * hazard-aware cost, and the hazards on the roads actually used.
 */
data class EvacuationRoute(
    val nodeIds: List<String>,
    val totalDistanceMeters: Double,
    val totalCostMeters: Double,
    val hazardWarnings: List<RoadHazard> = emptyList(),
)

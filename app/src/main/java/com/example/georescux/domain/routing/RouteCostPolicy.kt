package com.example.georescux.domain.routing

/**
 * Cost model for routing: normal cost is the edge distance, hazards add
 * penalties, and blocked roads are excluded entirely (they override every
 * other consideration). Pure Kotlin — no Android/UI behavior here.
 */
object RouteCostPolicy {

    /**
     * Hazard-aware cost of traversing an edge in meters, or null when the
     * edge is blocked and must never be traversed.
     */
    fun edgeCostMeters(edge: RouteEdge, hazards: List<RoadHazard>): Double? {
        if (edge.blocked) return null
        var cost = edge.distanceMeters
        hazards.forEach { hazard ->
            if (hazard.affects(edge.fromNodeId, edge.toNodeId)) {
                cost += hazard.penaltyMeters
            }
        }
        return cost
    }
}

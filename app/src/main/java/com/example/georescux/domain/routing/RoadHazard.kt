package com.example.georescux.domain.routing

/**
 * A hazard on the undirected road between two nodes. It adds a penalty
 * (in meters) to that edge's routing cost; the edge itself may still be
 * used when it is genuinely the best available route. Blocked roads are
 * handled by [RouteEdge.blocked], not by hazards.
 */
data class RoadHazard(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val penaltyMeters: Double,
) {
    /** True when this hazard applies to the undirected edge between the two nodes. */
    fun affects(fromNodeId: String, toNodeId: String): Boolean =
        (fromNodeId == this.fromNodeId && toNodeId == this.toNodeId) ||
            (toNodeId == this.fromNodeId && fromNodeId == this.toNodeId)
}

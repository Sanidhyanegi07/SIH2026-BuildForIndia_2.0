package com.example.georescux.domain.routing

/**
 * An undirected road between two nodes. [blocked] roads are excluded from
 * routing entirely and override every other cost consideration.
 */
data class RouteEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val distanceMeters: Double,
    val blocked: Boolean = false,
) {
    /** True when this undirected edge connects the two nodes in either direction. */
    fun connects(nodeA: String, nodeB: String): Boolean =
        (fromNodeId == nodeA && toNodeId == nodeB) || (fromNodeId == nodeB && toNodeId == nodeA)
}

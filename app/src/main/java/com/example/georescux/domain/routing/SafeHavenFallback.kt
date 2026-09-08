package com.example.georescux.domain.routing

/**
 * Safe-haven fallback: first attempt the requested destination; when it is
 * unreachable, route to the nearest reachable safe haven (by route cost).
 * Deterministic — equal-cost havens resolve by graph node order. Pure Kotlin.
 */
object SafeHavenFallback {

    fun findRoute(graph: RouteGraph, startId: String, destinationId: String): EvacuationRoute? {
        AStarPathfinder.findRoute(graph, startId, destinationId)?.let { return it }

        return graph.nodes
            .asSequence()
            .filter { it.isSafeHaven && it.id != startId }
            .mapNotNull { haven -> AStarPathfinder.findRoute(graph, startId, haven.id) }
            .minByOrNull { it.totalCostMeters }
    }
}

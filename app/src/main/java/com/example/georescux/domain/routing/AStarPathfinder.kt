package com.example.georescux.domain.routing

import java.util.PriorityQueue
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure A* pathfinder over [RouteGraph].
 *
 * - Costs come from [RouteCostPolicy] (distance + hazard penalties;
 *   blocked edges are never traversed).
 * - The heuristic is the straight-line (haversine) distance to the
 *   destination. It is admissible because every edge cost is at least the
 *   geographic distance of that edge.
 * - Ties are broken by node id, so equivalent paths resolve deterministically.
 * - No Android, Firebase, or UI dependencies.
 */
object AStarPathfinder {

    fun findRoute(graph: RouteGraph, startId: String, destinationId: String): EvacuationRoute? {
        val start = graph.node(startId) ?: return null
        val destination = graph.node(destinationId) ?: return null

        if (startId == destinationId) {
            // Trivial route: no roads needed.
            return EvacuationRoute(
                nodeIds = listOf(startId),
                totalDistanceMeters = 0.0,
                totalCostMeters = 0.0,
            )
        }

        val bestCost = mutableMapOf(startId to 0.0)
        val cameFrom = mutableMapOf<String, String>()
        val open = PriorityQueue<SearchNode>(
            compareBy({ it.estimatedTotalCost }, { it.nodeId })
        )
        open.add(SearchNode(startId, costSoFar = 0.0, estimatedTotalCost = haversineMeters(start, destination)))

        while (open.isNotEmpty()) {
            val current = open.poll() ?: break
            // Skip stale queue entries that were superseded by a cheaper path.
            if (current.costSoFar > (bestCost[current.nodeId] ?: Double.MAX_VALUE)) continue

            if (current.nodeId == destinationId) {
                return buildRoute(graph, cameFrom, destinationId)
            }

            for ((edge, neighbor) in graph.neighbors(current.nodeId)) {
                val edgeCost = RouteCostPolicy.edgeCostMeters(edge, graph.hazards) ?: continue
                val nextCost = current.costSoFar + edgeCost
                val knownBest = bestCost[neighbor.id]
                if (knownBest == null || nextCost < knownBest) {
                    bestCost[neighbor.id] = nextCost
                    cameFrom[neighbor.id] = current.nodeId
                    open.add(
                        SearchNode(
                            nodeId = neighbor.id,
                            costSoFar = nextCost,
                            estimatedTotalCost = nextCost + haversineMeters(neighbor, destination),
                        )
                    )
                }
            }
        }
        return null // destination unreachable
    }

    private fun buildRoute(
        graph: RouteGraph,
        cameFrom: Map<String, String>,
        destinationId: String,
    ): EvacuationRoute {
        val path = mutableListOf(destinationId)
        var current = destinationId
        while (cameFrom.containsKey(current)) {
            current = cameFrom.getValue(current)
            path.add(current)
        }
        path.reverse()

        var totalDistance = 0.0
        var totalCost = 0.0
        val warnings = linkedMapOf<String, RoadHazard>()
        path.zipWithNext { fromNodeId, toNodeId ->
            val edge = graph.edgeBetween(fromNodeId, toNodeId)!!
            totalDistance += edge.distanceMeters
            totalCost += RouteCostPolicy.edgeCostMeters(edge, graph.hazards) ?: 0.0
            graph.hazards
                .filter { it.affects(fromNodeId, toNodeId) }
                .forEach { warnings.putIfAbsent(it.id, it) }
        }
        return EvacuationRoute(
            nodeIds = path.toList(),
            totalDistanceMeters = totalDistance,
            totalCostMeters = totalCost,
            hazardWarnings = warnings.values.toList(),
        )
    }

    private data class SearchNode(
        val nodeId: String,
        val costSoFar: Double,
        val estimatedTotalCost: Double,
    )

    /** Straight-line distance — admissible for the distance-based cost model. */
    fun haversineMeters(from: RouteNode, to: RouteNode): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val halfChord = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) *
            sin(dLng / 2).pow(2)
        return 2 * earthRadiusMeters * asin(sqrt(halfChord))
    }
}

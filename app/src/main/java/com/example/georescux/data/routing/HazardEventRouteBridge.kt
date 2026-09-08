package com.example.georescux.data.routing

import com.example.georescux.domain.hazard.HazardCorroboration
import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentType
import com.example.georescux.domain.repository.RouteRepository
import com.example.georescux.domain.routing.NearestNode
import com.example.georescux.domain.routing.RoadHazard
import com.example.georescux.domain.routing.RouteEdge

/**
 * Connector that listens for accepted [IncidentEvent] hazards / route blocks,
 * verifies corroboration via [HazardCorroboration], and updates [RouteRepository]'s
 * hazard and edge-block state so subsequent A* pathfinder runs automatically route around them.
 */
class HazardEventRouteBridge(
    private val routeRepository: RouteRepository,
    private val corroboration: HazardCorroboration = HazardCorroboration(),
) {

    private val receivedEvents = mutableListOf<IncidentEvent>()

    fun onEventAccepted(event: IncidentEvent) {
        if (event.type != IncidentType.HAZARD && event.type != IncidentType.ROUTE_BLOCK) return
        receivedEvents.add(event)
        processEvents()
    }

    fun processEvents(nowMs: Long = System.currentTimeMillis()) {
        val clusters = corroboration.findCorroboratedClusters(receivedEvents, nowMs)

        clusters.filter { it.isCorroborated }.forEach { cluster ->
            val graph = routeRepository.getGraph()

            // 1. Explicit node endpoints provided in hazard payload
            if (cluster.fromNodeId != null && cluster.toNodeId != null) {
                applyHazardToEdge(cluster.fromNodeId, cluster.toNodeId, cluster)
                return@forEach
            }

            // 2. Spatial proximity fallback: find nearest graph edge to cluster coordinates
            val nearestEdge = findNearestEdge(graph.nodes, graph.edges, cluster.latitude, cluster.longitude)
            if (nearestEdge != null) {
                applyHazardToEdge(nearestEdge.fromNodeId, nearestEdge.toNodeId, cluster)
            }
        }
    }

    private fun applyHazardToEdge(fromNodeId: String, toNodeId: String, cluster: com.example.georescux.domain.hazard.CorroboratedHazardCluster) {
        val hasRouteBlockReport = cluster.reports.any { it.type == IncidentType.ROUTE_BLOCK }
        if (hasRouteBlockReport) {
            routeRepository.setRoadBlocked(fromNodeId, toNodeId, blocked = true)
        } else {
            val hazardId = "hazard_${fromNodeId}_${toNodeId}"
            val penaltyMeters = 500.0 * cluster.reportCount.coerceAtLeast(1)
            val roadHazard = RoadHazard(
                id = hazardId,
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                penaltyMeters = penaltyMeters,
            )
            routeRepository.setHazard(roadHazard)
        }
    }

    private fun findNearestEdge(
        nodes: List<com.example.georescux.domain.routing.RouteNode>,
        edges: List<RouteEdge>,
        lat: Double,
        lng: Double,
    ): RouteEdge? {
        val nodeMap = nodes.associateBy { it.id }
        return edges.minByOrNull { edge ->
            val from = nodeMap[edge.fromNodeId] ?: return@minByOrNull Double.MAX_VALUE
            val to = nodeMap[edge.toNodeId] ?: return@minByOrNull Double.MAX_VALUE
            val midLat = (from.latitude + to.latitude) / 2.0
            val midLng = (from.longitude + to.longitude) / 2.0
            HazardCorroboration.haversineMeters(lat, lng, midLat, midLng)
        }
    }
}

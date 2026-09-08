package com.example.georescux.data.routing

import com.example.georescux.domain.hazard.HazardCorroboration
import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentSeverity
import com.example.georescux.domain.incident.IncidentType
import com.example.georescux.domain.repository.RouteRepository
import com.example.georescux.domain.routing.AStarPathfinder
import com.example.georescux.domain.routing.RouteEdge
import com.example.georescux.domain.routing.RouteGraph
import com.example.georescux.domain.routing.RouteNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class HazardEventRouteBridgeTest {

    private lateinit var store: InMemoryRouteGraphStore
    private lateinit var routeRepository: RouteRepository
    private lateinit var bridge: HazardEventRouteBridge

    // Simple test graph:
    // A --- (edge1: 100m) ---> B --- (edge2: 100m) ---> C  (Direct route: 200m)
    // A --- (edge3: 150m) ---> D --- (edge4: 150m) ---> C  (Detour route: 300m)
    private val nodeA = RouteNode(id = "A", latitude = 28.600, longitude = 77.200)
    private val nodeB = RouteNode(id = "B", latitude = 28.601, longitude = 77.201)
    private val nodeC = RouteNode(id = "C", latitude = 28.602, longitude = 77.202, isSafeHaven = true)
    private val nodeD = RouteNode(id = "D", latitude = 28.605, longitude = 77.205)

    private val edgeAB = RouteEdge(fromNodeId = "A", toNodeId = "B", distanceMeters = 100.0)
    private val edgeBC = RouteEdge(fromNodeId = "B", toNodeId = "C", distanceMeters = 100.0)
    private val edgeAD = RouteEdge(fromNodeId = "A", toNodeId = "D", distanceMeters = 150.0)
    private val edgeDC = RouteEdge(fromNodeId = "D", toNodeId = "C", distanceMeters = 150.0)

    private val testGraph = RouteGraph(
        nodes = listOf(nodeA, nodeB, nodeC, nodeD),
        edges = listOf(edgeAB, edgeBC, edgeAD, edgeDC),
        version = 1,
    )

    @Before
    fun setUp() {
        store = InMemoryRouteGraphStore(testGraph)
        routeRepository = RouteRepositoryImpl(store = store, activeRegionId = "test_region")
        bridge = HazardEventRouteBridge(
            routeRepository = routeRepository,
            corroboration = HazardCorroboration(minReportsRequired = 2),
        )
    }

    @Test
    fun `initial route takes shortest direct path A-B-C`() {
        val initialRoute = AStarPathfinder.findRoute(routeRepository.getGraph(), "A", "C")
        assertNotNull(initialRoute)
        assertEquals(listOf("A", "B", "C"), initialRoute!!.nodeIds)
    }

    @Test
    fun `corroborated route block causes AStarPathfinder to take detour A-D-C`() {
        val nowMs = System.currentTimeMillis()

        // 2 independent reports blocking edge A-B
        val report1 = IncidentEvent(
            eventId = "h1",
            originId = "user1",
            type = IncidentType.ROUTE_BLOCK,
            occurredAtMs = nowMs,
            latitude = 28.6005,
            longitude = 77.2005,
            severity = IncidentSeverity.CRITICAL,
            payload = mapOf("fromNodeId" to "A", "toNodeId" to "B"),
        )
        val report2 = IncidentEvent(
            eventId = "h2",
            originId = "user2",
            type = IncidentType.ROUTE_BLOCK,
            occurredAtMs = nowMs,
            latitude = 28.6005,
            longitude = 77.2005,
            severity = IncidentSeverity.CRITICAL,
            payload = mapOf("fromNodeId" to "A", "toNodeId" to "B"),
        )

        bridge.onEventAccepted(report1)
        bridge.onEventAccepted(report2)

        val updatedGraph = routeRepository.getGraph()
        val reroutedPath = AStarPathfinder.findRoute(updatedGraph, "A", "C")

        assertNotNull(reroutedPath)
        assertEquals(listOf("A", "D", "C"), reroutedPath!!.nodeIds)
        assertNotEquals(listOf("A", "B", "C"), reroutedPath.nodeIds)
    }

    private class InMemoryRouteGraphStore(private var currentGraph: RouteGraph) : RouteGraphLocalStore {
        override fun loadGraph(regionId: String): RouteGraph? = currentGraph
        override fun saveGraph(regionId: String, graph: RouteGraph) {
            currentGraph = graph
        }
        override fun migrateLegacyGraph(regionId: String) {}
    }
}

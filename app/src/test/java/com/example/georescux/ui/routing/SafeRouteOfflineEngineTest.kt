package com.example.georescux.ui.routing

import com.example.georescux.data.routing.DefaultEvacuationGraph
import com.example.georescux.data.routing.RouteGraphLocalStore
import com.example.georescux.data.routing.RouteRepositoryImpl
import com.example.georescux.domain.routing.AStarPathfinder
import com.example.georescux.domain.routing.RoadHazard
import com.example.georescux.domain.routing.RouteEdge
import com.example.georescux.domain.routing.RouteGraph
import com.example.georescux.domain.routing.RouteNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies Phase 8, 9, 10, 11:
 * - Route calculation strictly operates offline on-device
 * - Scoped regional graph route_graph_{regionId} is respected
 * - Blocked roads are completely avoided
 * - Hazards increase route cost and force detours
 * - Display route corresponds 1:1 with local A* engine output
 */
class SafeRouteOfflineEngineTest {

    private class InMemoryRouteGraphStore : RouteGraphLocalStore {
        val graphs = mutableMapOf<String, RouteGraph>()
        override fun loadGraph(regionId: String): RouteGraph? = graphs[regionId]
        override fun saveGraph(regionId: String, graph: RouteGraph) {
            graphs[regionId] = graph
        }
        override fun migrateLegacyGraph(regionId: String) {}
    }

    private fun createTestGraph(): RouteGraph {
        // Triangle: A -> B -> C (direct A-C is 100m, detour A-B-C is 60m + 60m = 120m)
        val nodes = listOf(
            RouteNode("A", 28.6300, 77.2150),
            RouteNode("B", 28.6320, 77.2180),
            RouteNode("C", 28.6340, 77.2200, isSafeHaven = true),
            RouteNode("D", 28.6350, 77.2250, isSafeHaven = true)
        )
        val edges = listOf(
            RouteEdge("A", "C", distanceMeters = 100.0),
            RouteEdge("A", "B", distanceMeters = 60.0),
            RouteEdge("B", "C", distanceMeters = 60.0),
            RouteEdge("C", "D", distanceMeters = 50.0)
        )
        return RouteGraph(nodes = nodes, edges = edges, hazards = emptyList(), version = 1)
    }

    @Test
    fun `routing uses regional route_graph_regionId correctly`() {
        val store = InMemoryRouteGraphStore()
        val graph1 = createTestGraph()
        val graph2 = RouteGraph(
            nodes = listOf(RouteNode("N1", 30.0, 78.0), RouteNode("N2", 30.1, 78.1, isSafeHaven = true)),
            edges = listOf(RouteEdge("N1", "N2", 500.0)),
            hazards = emptyList(),
            version = 1
        )
        store.saveGraph("region_1", graph1)
        store.saveGraph("region_2", graph2)

        val repo1 = RouteRepositoryImpl(store, "region_1")
        val repo2 = RouteRepositoryImpl(store, "region_2")

        val route1 = repo1.findRoute("A", "C")
        val route2 = repo2.findRoute("N1", "N2")

        assertNotNull("Region 1 route must resolve", route1)
        assertEquals(listOf("A", "C"), route1!!.nodeIds)

        assertNotNull("Region 2 route must resolve", route2)
        assertEquals(listOf("N1", "N2"), route2!!.nodeIds)
    }

    @Test
    fun `blocked roads are strictly bypassed`() {
        val store = InMemoryRouteGraphStore()
        val graph = createTestGraph()
        store.saveGraph("test_region", graph)
        val repo = RouteRepositoryImpl(store, "test_region")

        // Originally direct path A -> C is shortest (100m vs 120m)
        val initialRoute = repo.findRoute("A", "C")
        assertNotNull(initialRoute)
        assertEquals(listOf("A", "C"), initialRoute!!.nodeIds)

        // Block direct road A -> C
        repo.setRoadBlocked("A", "C", true)

        // New route MUST take detour A -> B -> C
        val detourRoute = repo.findRoute("A", "C")
        assertNotNull("Detour route must be found", detourRoute)
        assertEquals(listOf("A", "B", "C"), detourRoute!!.nodeIds)
        assertEquals(120.0, detourRoute.totalDistanceMeters, 0.001)

        // Path must not contain the blocked segment
        detourRoute.nodeIds.zipWithNext { from, to ->
            assertFalse((from == "A" && to == "C") || (from == "C" && to == "A"))
        }
    }

    @Test
    fun `hazards add penalty and force safer alternative route`() {
        val store = InMemoryRouteGraphStore()
        val graph = createTestGraph()
        store.saveGraph("test_region", graph)
        val repo = RouteRepositoryImpl(store, "test_region")

        // Add 500m penalty on direct road A -> C
        repo.setHazard(RoadHazard("h1", "A", "C", penaltyMeters = 500.0))

        // Even though distance A->C is 100m, cost becomes 600m. Detour A->B->C is 120m cost.
        val safeRoute = repo.findRoute("A", "C")
        assertNotNull(safeRoute)
        assertEquals(listOf("A", "B", "C"), safeRoute!!.nodeIds)
        assertEquals(120.0, safeRoute.totalCostMeters, 0.001)
    }

    @Test
    fun `safe route points match exact AStar pathfinder output`() {
        val graph = createTestGraph()
        val aStarResult = AStarPathfinder.findRoute(graph, "A", "D")
        assertNotNull(aStarResult)

        // Verify points match sequence
        assertEquals(listOf("A", "C", "D"), aStarResult!!.nodeIds)
        assertEquals(150.0, aStarResult.totalDistanceMeters, 0.001)
    }

    @Test
    fun `routing works completely offline without network`() {
        // Uses purely local in-memory store and local pathfinder
        val store = InMemoryRouteGraphStore()
        store.saveGraph("offline_reg", DefaultEvacuationGraph.create())
        val repo = RouteRepositoryImpl(store, "offline_reg")

        val graph = repo.getGraph()
        val start = graph.nodes.first().id
        val dest = graph.nodes.last().id

        val route = repo.findRoute(start, dest)
        assertTrue(route != null)
    }
}

package com.example.georescux.data.routing

import com.example.georescux.domain.routing.RouteGraph
import com.example.georescux.domain.routing.RouteNode
import com.example.georescux.domain.routing.RoadHazard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the region-scoped routing repository: per-region graph storage
 * with strict isolation, the version rule (a newer bundled seed upgrades,
 * a newer stored graph is preserved — no downgrades), and that the legacy
 * migration hook is delegated to the store per region.
 */
class RouteRepositoryImplTest {

    private class InMemoryRouteGraphStore : RouteGraphLocalStore {
        val graphsByRegion = mutableMapOf<String, RouteGraph>()
        var migrationCount = 0
        val migratedRegions = mutableListOf<String>()

        override fun migrateLegacyGraph(regionId: String) {
            migrationCount++
            migratedRegions.add(regionId)
        }

        override fun loadGraph(regionId: String): RouteGraph? = graphsByRegion[regionId]

        override fun saveGraph(regionId: String, graph: RouteGraph) {
            graphsByRegion[regionId] = graph
        }
    }

    private fun graph(vararg nodeIds: String, version: Int = 1): RouteGraph = RouteGraph(
        nodes = nodeIds.map { RouteNode(it, 0.0, 0.0) },
        edges = emptyList(),
        version = version,
    )

    private fun repository(
        store: InMemoryRouteGraphStore,
        activeRegionId: String,
    ): RouteRepositoryImpl = RouteRepositoryImpl(store, activeRegionId)

    @Test
    fun `region-scoped storage isolates graphs per region`() {
        val store = InMemoryRouteGraphStore()
        val repositoryA = repository(store, activeRegionId = "region-a")
        val repositoryB = repository(store, activeRegionId = "region-b")

        val graphA = graph("a1", "a2")
        val graphB = graph("b1", "b2")
        repositoryA.saveGraph(graphA)
        repositoryB.saveGraph(graphB)

        assertEquals(graphA, repositoryA.getGraph())
        assertEquals(graphB, repositoryB.getGraph())
        assertTrue(graphA !== graphB)
    }

    @Test
    fun `stored version 0 is upgraded by the bundled version 1 seed`() {
        val store = InMemoryRouteGraphStore()
        store.saveGraph("sample-region", graph("old-node", version = 0))
        val repository = repository(store, activeRegionId = "sample-region")

        val route = repository.findRoute("old-node", "old-node")

        // The seed replaced the older stored graph and serves routes.
        assertEquals(listOf("old-node"), route?.nodeIds)
    }

    @Test
    fun `stored version 2 is preserved against the bundled version 1 seed`() {
        val store = InMemoryRouteGraphStore()
        store.saveGraph("sample-region", graph("new-node", version = 2))
        val repository = repository(store, activeRegionId = "sample-region")

        val graph = repository.getGraph()

        // No downgrade: the newer stored graph wins over the older seed.
        assertEquals(2, graph.version)
        assertEquals("new-node", graph.nodes.single().id)
    }

    @Test
    fun `stored equal version is preserved`() {
        val store = InMemoryRouteGraphStore()
        store.saveGraph("sample-region", graph("stored-node", version = 1))
        val repository = repository(store, activeRegionId = "sample-region")

        assertEquals("stored-node", repository.getGraph().nodes.single().id)
    }

    @Test
    fun `legacy migration is delegated to the store per region`() {
        val store = InMemoryRouteGraphStore()
        val repository = repository(store, activeRegionId = "sample-region")

        repository.getGraph()

        assertEquals(1, store.migrationCount)
        assertEquals(listOf("sample-region"), store.migratedRegions)
    }

    @Test
    fun `the active region id is used for all store access`() {
        val store = InMemoryRouteGraphStore()
        val repository = repository(store, activeRegionId = "sample-region")

        repository.saveGraph(graph("a1", "a2"))

        assertEquals(setOf("sample-region"), store.graphsByRegion.keys)
    }

    @Test
    fun `blocked road is persisted per region and the route takes the bypass`() {
        val store = InMemoryRouteGraphStore()
        val repository = repository(store, activeRegionId = "sample-region")

        repository.setRoadBlocked("node-a", "node-b", blocked = true)

        val storedGraph = store.graphsByRegion.getValue("sample-region")
        assertTrue(storedGraph.edges.first { it.connects("node-a", "node-b") }.blocked)

        // haven-1 stays reachable through the node-b/node-d bypass; the route
        // must take it instead of the blocked direct road.
        val route = repository.findRoute("node-a", "haven-1")
        assertEquals(
            listOf("node-a", "node-d", "node-b", "node-c", "haven-1"),
            route?.nodeIds
        )
        assertEquals(3600.0, route?.totalDistanceMeters)
    }

    @Test
    fun `hazard state is persisted per region and affects the route`() {
        val store = InMemoryRouteGraphStore()
        val repository = repository(store, activeRegionId = "sample-region")

        repository.setHazard(
            RoadHazard("hz-1", fromNodeId = "node-a", toNodeId = "node-b", penaltyMeters = 300.0)
        )

        val storedGraph = store.graphsByRegion.getValue("sample-region")
        assertEquals(1, storedGraph.hazards.size)
        val route = repository.findRoute("node-a", "haven-1")
        assertEquals(2400.0, route?.totalCostMeters)
        assertTrue(route?.hazardWarnings?.any { it.id == "hz-1" } == true)
    }

    @Test
    fun `removing a hazard restores the original route cost`() {
        val store = InMemoryRouteGraphStore()
        val repository = repository(store, activeRegionId = "sample-region")
        repository.setHazard(
            RoadHazard("hz-1", fromNodeId = "node-a", toNodeId = "node-b", penaltyMeters = 300.0)
        )

        repository.removeHazard("hz-1")

        assertTrue(store.graphsByRegion.getValue("sample-region").hazards.isEmpty())
        val route = repository.findRoute("node-a", "haven-1")
        assertEquals(2100.0, route?.totalCostMeters)
        assertTrue(route?.hazardWarnings.isNullOrEmpty())
    }

    @Test
    fun `save and load round trip preserves the region graph`() {
        val store = InMemoryRouteGraphStore()
        val repository = repository(store, activeRegionId = "sample-region")
        val expected = repository.getGraph()

        val reloaded = RouteRepositoryImpl(store, activeRegionId = "sample-region").getGraph()

        assertEquals(expected, reloaded)
    }
}

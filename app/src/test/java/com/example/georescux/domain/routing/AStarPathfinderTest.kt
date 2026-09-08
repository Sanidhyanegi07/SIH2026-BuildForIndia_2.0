package com.example.georescux.domain.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the pure A* pathfinder: shortest path, blocked-edge exclusion,
 * hazard-aware route changes, start==destination, unreachable destinations
 * and deterministic results.
 *
 * Test graphs place nodes on the equator, 0.001° apart (~111.19 m), so
 * edge distances of 150 m are always admissible (>= straight-line distance).
 */
class AStarPathfinderTest {

    private fun node(id: String, lat: Double = 0.0, lng: Double = 0.0) = RouteNode(id, lat, lng)

    private fun edge(from: String, to: String, meters: Double, blocked: Boolean = false) =
        RouteEdge(from, to, meters, blocked)

    private fun lineGraph(): RouteGraph = RouteGraph(
        nodes = listOf(
            node("a"),
            node("b", lat = 0.001),
            node("c", lat = 0.002),
            node("d", lat = 0.003),
        ),
        edges = listOf(
            edge("a", "b", 150.0),
            edge("b", "c", 150.0),
            edge("c", "d", 150.0),
            edge("a", "c", 400.0), // direct but longer than a-b-c
        ),
    )

    @Test
    fun `finds the shortest path and correct total cost`() {
        val route = AStarPathfinder.findRoute(lineGraph(), "a", "c")

        assertEquals(listOf("a", "b", "c"), route?.nodeIds)
        assertEquals(300.0, route?.totalDistanceMeters)
        assertEquals(300.0, route?.totalCostMeters)
    }

    @Test
    fun `continues along the graph to a later destination`() {
        val route = AStarPathfinder.findRoute(lineGraph(), "a", "d")

        assertEquals(listOf("a", "b", "c", "d"), route?.nodeIds)
        assertEquals(450.0, route?.totalDistanceMeters)
    }

    @Test
    fun `blocked edge is excluded and an alternative is used`() {
        val graph = lineGraph().copy(
            edges = lineGraph().edges.map { edge ->
                if (edge.connects("a", "b")) edge.copy(blocked = true) else edge
            }
        )

        val route = AStarPathfinder.findRoute(graph, "a", "c")

        assertEquals(listOf("a", "c"), route?.nodeIds)
        assertEquals(400.0, route?.totalCostMeters)
    }

    @Test
    fun `a blocked edge with no alternative makes the destination unreachable`() {
        val graph = RouteGraph(
            nodes = listOf(node("a"), node("b", lat = 0.001)),
            edges = listOf(edge("a", "b", 150.0, blocked = true)),
        )

        assertNull(AStarPathfinder.findRoute(graph, "a", "b"))
    }

    @Test
    fun `a large hazard penalty changes the selected route`() {
        val hazard = RoadHazard("hz-1", "a", "b", penaltyMeters = 300.0)
        val graph = lineGraph().copy(hazards = listOf(hazard))

        // a-b now costs 150 + 300 = 450, so the direct a-c road (400) wins.
        val route = AStarPathfinder.findRoute(graph, "a", "c")

        assertEquals(listOf("a", "c"), route?.nodeIds)
        assertEquals(400.0, route?.totalCostMeters)
    }

    @Test
    fun `a small hazard penalty keeps the route and produces a warning`() {
        val hazard = RoadHazard("hz-1", "a", "b", penaltyMeters = 10.0)
        val graph = lineGraph().copy(hazards = listOf(hazard))

        val route = AStarPathfinder.findRoute(graph, "a", "c")

        assertEquals(listOf("a", "b", "c"), route?.nodeIds)
        assertEquals(310.0, route?.totalCostMeters)
        assertEquals(listOf(hazard), route?.hazardWarnings)
    }

    @Test
    fun `start equals destination returns a zero-length route`() {
        val route = AStarPathfinder.findRoute(lineGraph(), "a", "a")

        assertEquals(listOf("a"), route?.nodeIds)
        assertEquals(0.0, route?.totalDistanceMeters)
        assertEquals(0.0, route?.totalCostMeters)
    }

    @Test
    fun `unreachable destination returns null`() {
        val graph = RouteGraph(
            nodes = listOf(node("a"), node("z", lat = 5.0)),
            edges = emptyList(),
        )

        assertNull(AStarPathfinder.findRoute(graph, "a", "z"))
    }

    @Test
    fun `unknown nodes return null`() {
        val graph = lineGraph()

        assertNull(AStarPathfinder.findRoute(graph, "a", "nope"))
        assertNull(AStarPathfinder.findRoute(graph, "nope", "a"))
    }

    @Test
    fun `equivalent paths resolve deterministically`() {
        // Diamond: a-n1-c and a-n2-c both cost 200.
        val graph = RouteGraph(
            nodes = listOf(
                node("a"),
                node("n1", lat = 0.001),
                node("n2", lng = 0.001),
                node("c", lat = 0.001, lng = 0.001),
            ),
            edges = listOf(
                edge("a", "n1", 150.0),
                edge("n1", "c", 150.0),
                edge("a", "n2", 150.0),
                edge("n2", "c", 150.0),
            ),
        )

        val first = AStarPathfinder.findRoute(graph, "a", "c")
        val second = AStarPathfinder.findRoute(graph, "a", "c")

        assertEquals(first, second)
        assertEquals(300.0, first?.totalCostMeters)
    }
}

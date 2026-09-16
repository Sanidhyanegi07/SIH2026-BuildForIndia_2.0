package com.example.georescux.domain.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off-route detection (spec §15/§16): distance from a live fix to the
 * route polyline, and the on/off-route decision.
 */
class RouteDeviationDetectorTest {

    private val graph = RouteGraph(
        nodes = listOf(
            RouteNode("a", 0.0, 0.0),
            RouteNode("b", 0.001, 0.0), // ~111 m east of a
            RouteNode("c", 0.002, 0.0),
        ),
        edges = listOf(
            RouteEdge("a", "b", 111.0),
            RouteEdge("b", "c", 111.0),
        ),
    )

    private fun route(vararg ids: String) = EvacuationRoute(
        nodeIds = ids.toList(),
        totalDistanceMeters = 222.0,
        totalCostMeters = 222.0,
        hazardWarnings = emptyList(),
    )

    @Test
    fun `a fix on the route has a near-zero distance and is on-route`() {
        val route = route("a", "b", "c")

        // A fix at node b should be considered on-route.
        val distance = RouteDeviationDetector.distanceToRouteMeters(0.001, 0.0, route, graph)
        assertTrue("distance $distance m should be tiny", distance < 50.0)
        assertFalse(RouteDeviationDetector.isOffRoute(0.001, 0.0, route, graph))
    }

    @Test
    fun `a fix far from the route is off-route`() {
        val route = route("a", "b", "c")

        // ~0.01 degree latitude north ≈ 1.1 km from the route that runs east.
        val distance = RouteDeviationDetector.distanceToRouteMeters(0.01, 0.0, route, graph)
        assertTrue("distance $distance m should exceed the threshold", distance > 250.0)
        assertTrue(RouteDeviationDetector.isOffRoute(0.01, 0.0, route, graph))
    }

    @Test
    fun `a fix near the route midpoint stays on-route even when not on a node`() {
        val route = route("a", "b", "c")

        // Between a and b, offset a little north — still well inside 250 m.
        val distance = RouteDeviationDetector.distanceToRouteMeters(0.0005, 0.0005, route, graph)
        assertTrue("distance $distance m should be within the threshold", distance < 250.0)
        assertFalse(RouteDeviationDetector.isOffRoute(0.0005, 0.0005, route, graph))
    }

    @Test
    fun `a degenerate single-node route reports no usable distance and so counts as off-route`() {
        val single = route("a")

        // Fewer than two points: there is no polyline to measure against, so
        // distance is undefined and the fix cannot be confirmed on-route.
        assertEquals(Double.MAX_VALUE, RouteDeviationDetector.distanceToRouteMeters(5.0, 5.0, single, graph), 0.0)
        assertTrue(RouteDeviationDetector.isOffRoute(5.0, 5.0, single, graph))
    }

    @Test
    fun `the threshold can be overridden`() {
        val route = route("a", "b", "c")

        // A ~555 m north offset: on-route at 1000 m, off-route at 100 m.
        assertFalse(RouteDeviationDetector.isOffRoute(0.005, 0.0, route, graph, thresholdMeters = 1000.0))
        assertTrue(RouteDeviationDetector.isOffRoute(0.005, 0.0, route, graph, thresholdMeters = 100.0))
    }
}

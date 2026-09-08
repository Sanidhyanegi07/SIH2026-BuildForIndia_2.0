package com.example.georescux.domain.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the safe-haven fallback: the requested destination first, the
 * nearest reachable safe haven second, and null when nothing is reachable.
 */
class SafeHavenFallbackTest {

    private fun node(id: String, lat: Double = 0.0, lng: Double = 0.0, safe: Boolean = false) =
        RouteNode(id, lat, lng, safe)

    private fun edge(from: String, to: String, meters: Double) = RouteEdge(from, to, meters)

    /**
     * a - b - h1(safe), a - c - h2(safe), plus a disconnected node x.
     * Route costs: a->h1 = 300 (via b), a->h2 = 350 (via c).
     */
    private fun graph(): RouteGraph = RouteGraph(
        nodes = listOf(
            node("a"),
            node("b", lat = 0.001),
            node("h1", lat = 0.002, safe = true),
            node("c", lat = 0.001, lng = 0.001),
            node("h2", lat = 0.002, lng = 0.001, safe = true),
            node("x", lat = 0.003),
        ),
        edges = listOf(
            edge("a", "b", 150.0),
            edge("b", "h1", 150.0),
            edge("a", "c", 200.0),
            edge("c", "h2", 150.0),
        ),
    )

    @Test
    fun `a reachable destination is routed directly`() {
        val route = SafeHavenFallback.findRoute(graph(), "a", "b")

        assertEquals(listOf("a", "b"), route?.nodeIds)
    }

    @Test
    fun `an unreachable destination falls back to the nearest reachable safe haven`() {
        val route = SafeHavenFallback.findRoute(graph(), "a", "x")

        // h1 (cost 300) is nearer than h2 (cost 350).
        assertEquals("h1", route?.nodeIds?.last())
        assertEquals(300.0, route?.totalCostMeters)
    }

    @Test
    fun `no reachable safe haven returns null`() {
        val graph = RouteGraph(
            nodes = listOf(node("a"), node("h1", lat = 0.002, safe = true)),
            edges = emptyList(),
        )

        assertNull(SafeHavenFallback.findRoute(graph, "a", "x"))
    }

    @Test
    fun `the fallback is deterministic`() {
        val first = SafeHavenFallback.findRoute(graph(), "a", "x")
        val second = SafeHavenFallback.findRoute(graph(), "a", "x")

        assertEquals(first, second)
    }

    @Test
    fun `a graph with no safe havens and an unreachable destination returns null`() {
        val graph = RouteGraph(
            nodes = listOf(node("a"), node("x", lat = 0.003)),
            edges = emptyList(),
        )

        assertNull(SafeHavenFallback.findRoute(graph, "a", "x"))
    }

    @Test
    fun `the fallback still prefers the requested safe haven destination when reachable`() {
        val route = SafeHavenFallback.findRoute(graph(), "a", "h1")

        assertEquals(listOf("a", "b", "h1"), route?.nodeIds)
    }
}

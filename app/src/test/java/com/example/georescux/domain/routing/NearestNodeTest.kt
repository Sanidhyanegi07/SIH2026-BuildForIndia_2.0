package com.example.georescux.domain.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the nearest-node selection used for the GPS start location:
 * Haversine distance, empty-graph safety and deterministic tie-breaking.
 */
class NearestNodeTest {

    private fun graph(vararg nodes: RouteNode) = RouteGraph(nodes = nodes.toList(), edges = emptyList())

    @Test
    fun `finds the nearest node by haversine distance`() {
        val graph = graph(
            RouteNode("far", 0.010, 0.010),
            RouteNode("near", 0.001, 0.001),
            RouteNode("middle", 0.005, 0.005),
        )

        assertEquals("near", NearestNode.find(graph, 0.0, 0.0)?.id)
    }

    @Test
    fun `empty graph returns null`() {
        assertNull(NearestNode.find(RouteGraph(nodes = emptyList(), edges = emptyList()), 0.0, 0.0))
    }

    @Test
    fun `equal distances resolve deterministically by node id`() {
        val graph = graph(
            RouteNode("node-b", 0.001, 0.0),
            RouteNode("node-a", -0.001, 0.0),
        )

        // Both nodes are ~111 m from the origin; node-a wins by id order.
        assertEquals("node-a", NearestNode.find(graph, 0.0, 0.0)?.id)
    }

    @Test
    fun `far away positions still resolve to the closest node`() {
        val graph = graph(
            RouteNode("delhi", 28.6329, 77.2196),
            RouteNode("mumbai", 19.0760, 72.8777),
        )

        assertEquals("delhi", NearestNode.find(graph, 28.61, 77.22)?.id)
        assertEquals("mumbai", NearestNode.find(graph, 19.08, 72.88)?.id)
    }
}

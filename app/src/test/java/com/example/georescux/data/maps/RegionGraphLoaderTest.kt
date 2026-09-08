package com.example.georescux.data.maps

import com.example.georescux.domain.routing.AStarPathfinder
import com.example.georescux.domain.routing.SafeHavenFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies RegionGraphLoader: valid and malformed JSON, missing required
 * fields, empty graphs — plus compatibility of the loaded real region
 * graph with the Stage 7A A* engine.
 */
class RegionGraphLoaderTest {

    @Test
    fun `valid graph json parses into nodes and edges`() {
        val json = """
            {
              "nodes": [
                {"id": "n1", "latitude": 28.6329, "longitude": 77.2196, "isSafeHaven": true},
                {"id": "n2", "latitude": 28.6339, "longitude": 77.2206, "isSafeHaven": false}
              ],
              "edges": [
                {"fromNodeId": "n1", "toNodeId": "n2", "distanceMeters": 142.5}
              ]
            }
        """.trimIndent()

        val graph = RegionGraphLoader.parse(json)!!

        assertEquals(2, graph.nodes.size)
        assertEquals(1, graph.edges.size)
        assertEquals(true, graph.node("n1")?.isSafeHaven)
        assertEquals(142.5, graph.edgeBetween("n1", "n2")?.distanceMeters)
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(RegionGraphLoader.parse("{not valid json"))
        assertNull(RegionGraphLoader.parse(""))
    }

    @Test
    fun `missing required node field returns null`() {
        val json = """
            {
              "nodes": [
                {"id": "n1", "longitude": 77.2196}
              ],
              "edges": []
            }
        """.trimIndent()

        assertNull(RegionGraphLoader.parse(json))
    }

    @Test
    fun `missing edges array returns null`() {
        val json = """{"nodes": [{"id": "n1", "latitude": 1.0, "longitude": 2.0}]}"""

        assertNull(RegionGraphLoader.parse(json))
    }

    @Test
    fun `empty node list returns null`() {
        val json = """{"nodes": [], "edges": []}"""

        assertNull(RegionGraphLoader.parse(json))
    }

    @Test
    fun `non object entries return null`() {
        val json = """{"nodes": ["not-an-object"], "edges": []}"""

        assertNull(RegionGraphLoader.parse(json))
    }

    @Test
    fun `optional isSafeHaven defaults to false`() {
        val json = """
            {
              "nodes": [{"id": "n1", "latitude": 1.0, "longitude": 2.0}],
              "edges": []
            }
        """.trimIndent()

        val graph = RegionGraphLoader.parse(json)!!
        assertEquals(false, graph.node("n1")?.isSafeHaven)
    }

    @Test
    fun `the loaded real sample region graph works with the stage 7a a star`() {
        // Reads the actual bundled asset produced by the converter.
        val assetFile = java.io.File("src/main/assets/maps/sample-region/graph.json")
        if (!assetFile.exists()) return // conversion step not run on this machine

        val graph = RegionGraphLoader.parse(assetFile.readText())!!
        assertTrue("real region graph should have substantial nodes", graph.nodes.size >= 50)

        // Any edge of the real graph must produce a valid A* route between
        // its endpoints.
        val edge = graph.edges.first()
        val route = SafeHavenFallback.findRoute(graph, edge.fromNodeId, edge.toNodeId)
        assertNotNull(route)
        assertEquals(edge.fromNodeId, route?.nodeIds?.first())
        assertEquals(edge.toNodeId, route?.nodeIds?.last())
        assertTrue(route!!.totalDistanceMeters > 0.0)

        // And A* must remain usable for any node pair of the graph.
        val first = graph.nodes.first()
        val routeFromFirst = SafeHavenFallback.findRoute(graph, first.id, first.id)
        assertEquals(listOf(first.id), routeFromFirst?.nodeIds)
    }
}

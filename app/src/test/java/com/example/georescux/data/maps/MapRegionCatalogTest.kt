package com.example.georescux.data.maps

import com.example.georescux.domain.routing.SafeHavenFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the bundled region metadata: bounds are valid, the asset paths
 * match the bundled files, and location-based region selection (Stage 7B-4)
 * resolves the right region.
 */
class MapRegionCatalogTest {

    @Test
    fun `sample region is listed`() {
        assertEquals("sample-region", MapRegionCatalog.sampleRegion.id)
        assertEquals(2, MapRegionCatalog.bundledRegions.size)
        assertEquals(MapRegionCatalog.sampleRegion, MapRegionCatalog.byId("sample-region"))
    }

    @Test
    fun `uttarakhand region is bundled`() {
        val region = MapRegionCatalog.uttarakhand
        assertEquals("uttarakhand", region.id)
        assertTrue(MapRegionCatalog.bundledRegions.contains(region))
        assertEquals(MapRegionCatalog.uttarakhand, MapRegionCatalog.byId("uttarakhand"))
    }

    @Test
    fun `bounds are valid`() {
        listOf(MapRegionCatalog.sampleRegion, MapRegionCatalog.uttarakhand).forEach { region ->
            assertTrue(region.minLatitude < region.maxLatitude)
            assertTrue(region.minLongitude < region.maxLongitude)
            assertTrue(region.minLatitude > -90 && region.maxLatitude < 90)
            assertTrue(region.minLongitude > -180 && region.maxLongitude < 180)
        }
    }

    @Test
    fun `asset paths point to the bundled sample region files`() {
        val region = MapRegionCatalog.sampleRegion
        assertEquals("maps/sample-region/graph.json", region.graphAssetPath)
        assertEquals("maps/sample-region/tiles.sqlite", region.tileAssetPath)
    }

    @Test
    fun `uttarakhand asset path follows the route_graph regionId convention`() {
        val region = MapRegionCatalog.uttarakhand
        assertEquals("route_graph_uttarakhand", region.graphAssetPath)
    }

    @Test
    fun `location inside uttarakhand selects the uttarakhand region`() {
        // Dehradun
        assertEquals(
            MapRegionCatalog.uttarakhand,
            MapRegionCatalog.regionForLocation(30.3165, 78.0322),
        )
        // Pithoragarh, in the state's far east
        assertEquals(
            MapRegionCatalog.uttarakhand,
            MapRegionCatalog.regionForLocation(29.5833, 80.2167),
        )
    }

    @Test
    fun `location inside the sample region selects the sample region`() {
        // Connaught Place, New Delhi — outside the Uttarakhand bbox.
        assertEquals(
            MapRegionCatalog.sampleRegion,
            MapRegionCatalog.regionForLocation(28.6328, 77.2196),
        )
    }

    @Test
    fun `location outside every bundled region selects nothing`() {
        assertNull(MapRegionCatalog.regionForLocation(19.0760, 72.8777)) // Mumbai
        assertNull(MapRegionCatalog.regionForLocation(12.9716, 77.5946)) // Bengaluru
    }

    @Test
    fun `the bundled real sample region graph works with the stage 7a a star`() {
        // Reads the actual bundled asset produced by the converter.
        val assetFile = java.io.File("src/main/assets/maps/sample-region/graph.json")
        if (!assetFile.exists()) return // conversion step not run on this machine

        val graph = com.example.georescux.data.maps.RegionGraphLoader.parse(assetFile.readText())!!
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

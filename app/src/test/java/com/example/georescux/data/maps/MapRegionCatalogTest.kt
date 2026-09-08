package com.example.georescux.data.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the bundled sample region metadata: bounds are valid and the
 * asset paths match the bundled files.
 */
class MapRegionCatalogTest {

    @Test
    fun `sample region is listed`() {
        assertEquals("sample-region", MapRegionCatalog.sampleRegion.id)
        assertEquals(1, MapRegionCatalog.bundledRegions.size)
        assertEquals(MapRegionCatalog.sampleRegion, MapRegionCatalog.byId("sample-region"))
    }

    @Test
    fun `bounds are valid`() {
        val region = MapRegionCatalog.sampleRegion
        assertTrue(region.minLatitude < region.maxLatitude)
        assertTrue(region.minLongitude < region.maxLongitude)
        assertTrue(region.minLatitude > -90 && region.maxLatitude < 90)
        assertTrue(region.minLongitude > -180 && region.maxLongitude < 180)
    }

    @Test
    fun `asset paths point to the bundled sample region files`() {
        val region = MapRegionCatalog.sampleRegion
        assertEquals("maps/sample-region/graph.json", region.graphAssetPath)
        assertEquals("maps/sample-region/tiles.sqlite", region.tileAssetPath)
    }
}

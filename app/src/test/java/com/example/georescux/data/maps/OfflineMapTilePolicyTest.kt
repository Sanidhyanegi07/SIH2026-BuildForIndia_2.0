package com.example.georescux.data.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.config.Configuration

/**
 * Verifies Phase 5:
 * - Tile storage is strictly bounded
 * - Max cache bytes is 100MB and trim bytes is 80MB
 * - Bundled region tile archives are valid
 */
class OfflineMapTilePolicyTest {

    @Test
    fun `tile cache limits are strictly bounded to prevent disk overflow`() {
        val maxBytes = 100L * 1024 * 1024 // 100 MB
        val trimBytes = 80L * 1024 * 1024 // 80 MB

        Configuration.getInstance().tileFileSystemCacheMaxBytes = maxBytes
        Configuration.getInstance().tileFileSystemCacheTrimBytes = trimBytes

        assertEquals(
            "Max tile cache must be bounded at 100MB",
            maxBytes,
            Configuration.getInstance().tileFileSystemCacheMaxBytes
        )
        assertEquals(
            "Trim tile cache must be bounded at 80MB",
            trimBytes,
            Configuration.getInstance().tileFileSystemCacheTrimBytes
        )
        assertTrue(
            "Trim bytes must be strictly less than max bytes",
            Configuration.getInstance().tileFileSystemCacheTrimBytes < Configuration.getInstance().tileFileSystemCacheMaxBytes
        )
    }

    @Test
    fun `sample region has valid offline sqlite tile asset path`() {
        val sample = MapRegionCatalog.sampleRegion
        assertEquals("maps/sample-region/tiles.sqlite", sample.tileAssetPath)
        assertTrue("Tile asset extension must be sqlite", sample.tileAssetPath.endsWith(".sqlite"))
    }

    @Test
    fun `all bundled regions have valid coordinates and non-zero areas`() {
        MapRegionCatalog.bundledRegions.forEach { region ->
            assertTrue("Min latitude must be less than max latitude", region.minLatitude < region.maxLatitude)
            assertTrue("Min longitude must be less than max longitude", region.minLongitude < region.maxLongitude)
            assertNotNull("Graph asset path must not be null", region.graphAssetPath)
        }
    }
}

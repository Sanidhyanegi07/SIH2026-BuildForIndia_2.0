package com.example.georescux.data.routing

import com.example.georescux.data.maps.RegionGraphLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure legacy-migration decision matrix (the SharedPreferences
 * store executes these outcomes against its real prefs):
 *
 * - valid legacy graph + no region graph  → migrate into the region key
 * - legacy graph + existing region graph  → keep the region graph, retire legacy
 * - corrupt legacy graph                  → retire legacy, nothing written
 * - no legacy key                         → no-op
 *
 * The migration is idempotent: after the first run the legacy key is
 * removed, so every subsequent run resolves to the no-op case.
 */
class RouteGraphLegacyMigratorTest {

    private val legacyGraphJson = """
        {
          "nodes": [
            {"id": "legacy-a", "latitude": 1.0, "longitude": 2.0, "isSafeHaven": false},
            {"id": "legacy-b", "latitude": 3.0, "longitude": 4.0, "isSafeHaven": false}
          ],
          "edges": [
            {"fromNodeId": "legacy-a", "toNodeId": "legacy-b", "distanceMeters": 500.0}
          ]
        }
    """.trimIndent()

    @Test
    fun `valid legacy graph migrates when the region key is absent`() {
        val outcome = RouteGraphLegacyMigrator.resolve(legacyGraphJson, regionGraphExists = false)

        assertTrue(outcome.retireLegacyKey)
        val migrated = outcome.writeToRegion!!
        assertEquals(listOf("legacy-a", "legacy-b"), migrated.nodes.map { it.id })
        assertEquals(
            500.0,
            migrated.edges.single().distanceMeters,
            0.0000001
        )
    }

    @Test
    fun `malformed legacy json is retired without migrating`() {
        val outcome = RouteGraphLegacyMigrator.resolve("{corrupt", regionGraphExists = false)

        assertNull(outcome.writeToRegion)
        assertTrue(outcome.retireLegacyKey)
    }

    @Test
    fun `an existing region graph wins over the legacy graph`() {
        val outcome = RouteGraphLegacyMigrator.resolve(legacyGraphJson, regionGraphExists = true)

        assertNull(outcome.writeToRegion)
        assertTrue(outcome.retireLegacyKey)
    }

    @Test
    fun `absent legacy key is a no-op`() {
        val outcome = RouteGraphLegacyMigrator.resolve(null, regionGraphExists = false)

        assertNull(outcome.writeToRegion)
        assertFalse(outcome.retireLegacyKey)
    }

    @Test
    fun `resolved outcome is deterministic`() {
        val first = RouteGraphLegacyMigrator.resolve(legacyGraphJson, regionGraphExists = false)
        val second = RouteGraphLegacyMigrator.resolve(legacyGraphJson, regionGraphExists = false)

        assertEquals(first, second)
    }
}

package com.example.georescux.data.routing

import com.example.georescux.data.maps.RegionGraphLoader
import com.example.georescux.domain.routing.RoadHazard
import com.example.georescux.domain.routing.RouteGraph

/**
 * Outcome of the one-time legacy graph migration.
 *
 * @property writeToRegion graph to store under the region key, or null when
 *   nothing may be written (corrupt data, or the region graph already exists).
 * @property retireLegacyKey true when the legacy "route_graph" key should be
 *   removed (migrated or unsalvageable), false when there was no legacy data.
 */
data class LegacyMigration(val writeToRegion: RouteGraph?, val retireLegacyKey: Boolean)

/**
 * Pure decision logic for the one-time legacy graph migration. No Android,
 * Firebase, SharedPreferences, or org.json code — fully JVM testable.
 */
object RouteGraphLegacyMigrator {

    /**
     * @param legacyJson raw JSON found under the pre-7B-2 "route_graph" key
     *   (null when the key does not exist).
     * @param regionGraphExists true when a graph is already stored under the
     *   target region key — an existing region graph is never overwritten.
     * @return what to write for the region (null = keep whatever is there /
     *   store nothing) and whether the legacy key may be retired.
     */
    fun resolve(legacyJson: String?, regionGraphExists: Boolean): LegacyMigration {
        if (legacyJson == null) return LegacyMigration(writeToRegion = null, retireLegacyKey = false)

        val legacyGraph = runCatching { RegionGraphLoader.parse(legacyJson) }.getOrNull()
            ?: // Unsalvageable legacy data: retire the key and let normal
            // region seeding produce a usable graph.
            return LegacyMigration(writeToRegion = null, retireLegacyKey = true)

        // An existing region graph wins: never overwrite it with the legacy
        // graph (requirement: the region-scoped graph must not be downgraded
        // or replaced by stale data).
        if (regionGraphExists) return LegacyMigration(writeToRegion = null, retireLegacyKey = true)

        return LegacyMigration(writeToRegion = legacyGraph, retireLegacyKey = true)
    }
}

package com.example.georescux.domain.routing

/**
 * Metadata for one offline map region.
 *
 * Stage 7B-1 ships a single bundled sample region (real OSM-derived data
 * for a small area); region packages and downloads come later.
 */
data class MapRegion(
    val id: String,
    val displayName: String,
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
    val version: Int,
    val graphAssetPath: String,
    val tileAssetPath: String,
)

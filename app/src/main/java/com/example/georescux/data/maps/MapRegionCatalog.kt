package com.example.georescux.data.maps

import com.example.georescux.domain.routing.MapRegion

/**
 * Lists the map regions bundled with the application.
 * Stage 7B-1: one small real OSM-derived sample region (Connaught Place,
 * New Delhi). Region packages and downloads belong to later stages.
 */
object MapRegionCatalog {

    val sampleRegion = MapRegion(
        id = "sample-region",
        displayName = "Connaught Place, New Delhi (sample)",
        minLatitude = 28.6290,
        maxLatitude = 28.6365,
        minLongitude = 77.2130,
        maxLongitude = 77.2270,
        version = 1,
        graphAssetPath = "maps/sample-region/graph.json",
        tileAssetPath = "maps/sample-region/tiles.sqlite",
    )

    val bundledRegions: List<MapRegion> = listOf(sampleRegion)

    fun byId(id: String): MapRegion? = bundledRegions.firstOrNull { it.id == id }
}

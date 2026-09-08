package com.example.georescux.data.maps

import com.example.georescux.domain.routing.MapRegion

/**
 * Lists the map regions bundled with the application.
 * Stage 7B-1: one small real OSM-derived sample region (Connaught Place,
 * New Delhi). Region packages and downloads belong to later stages.
 */
object MapRegionCatalog {
    val uttarakhand = MapRegion(
        id = "uttarakhand",
        displayName = "Uttarakhand",
        minLatitude = 28.7, maxLatitude = 31.5,
        minLongitude = 77.5, maxLongitude = 81.1,
        version = 1,
        graphAssetPath = "route_graph_uttarakhand",
        tileAssetPath = ""
    )

    val himachalPradesh = MapRegion(
        id = "himachal_pradesh",
        displayName = "Himachal Pradesh",
        minLatitude = 30.3, maxLatitude = 33.3,
        minLongitude = 75.5, maxLongitude = 79.0,
        version = 1,
        graphAssetPath = "route_graph_himachal_pradesh",
        tileAssetPath = ""
    )

    val haryana = MapRegion(
        id = "haryana",
        displayName = "Haryana",
        minLatitude = 27.6, maxLatitude = 30.9,
        minLongitude = 74.4, maxLongitude = 77.6,
        version = 1,
        graphAssetPath = "route_graph_haryana",
        tileAssetPath = ""
    )

    val uttarPradesh = MapRegion(
        id = "uttar_pradesh",
        displayName = "Uttar Pradesh",
        minLatitude = 23.8, maxLatitude = 30.4,
        minLongitude = 77.0, maxLongitude = 84.6,
        version = 1,
        graphAssetPath = "route_graph_uttar_pradesh",
        tileAssetPath = ""
    )

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

    val bundledRegions: List<MapRegion> = listOf(sampleRegion, uttarakhand)
    val availableRegions: List<MapRegion> = listOf(sampleRegion, uttarakhand, himachalPradesh, haryana, uttarPradesh)

    fun byId(id: String): MapRegion? = bundledRegions.firstOrNull { it.id == id } ?: availableRegions.firstOrNull { it.id == id }

    /**
     * The bundled region containing the given position, or null when the
     * position lies outside every bundled region. Used to auto-select the
     * regional graph from a location fix; manual selection always remains
     * possible via [byId].
     */
    fun regionForLocation(latitude: Double, longitude: Double): MapRegion? =
        bundledRegions.firstOrNull { region ->
            latitude in region.minLatitude..region.maxLatitude &&
                longitude in region.minLongitude..region.maxLongitude
        }
}

package com.example.georescux.ui.common

import android.app.Activity
import com.example.georescux.data.maps.MapRegionCatalog
import com.example.georescux.data.maps.TileArchiveInstaller
import com.example.georescux.domain.routing.MapRegion
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File

/**
 * Configures an osmdroid [MapView] for a region using the shared three-tier
 * offline strategy, so the Map screen and Safe Route render identically:
 *
 *  1. Mapsforge vector basemap (fully offline)
 *  2. Bundled SQLite raster archive (fully offline)
 *  3. OSM Mapnik with on-device caching (connection needed)
 *
 * The tier actually used is reported so each screen can show an honest badge
 * and, when no offline package exists for the region, tell the user instead
 * of silently implying the map works offline (spec §17 honesty rule).
 */
object OfflineMapSetup {

    enum class Tier(val badgeText: String) {
        VECTOR("Offline Vector Map"),
        SQLITE_ARCHIVE("Offline SQLite Map"),
        ONLINE("OSM Mapnik (Cached)"),
    }

    data class Result(val tier: Tier) {
        val isOffline: Boolean get() = tier != Tier.ONLINE
    }

    /**
     * Must be called once per activity before the map is used. Idempotent for
     * the graphic factory; safe to call from either screen.
     */
    fun prepareOsmdroid(activity: Activity) {
        val osmdroidBase = File(activity.filesDir, "osmdroid")
        Configuration.getInstance().osmdroidBasePath = osmdroidBase
        Configuration.getInstance().osmdroidTileCache = File(osmdroidBase, "tiles")
        Configuration.getInstance().userAgentValue =
            "GeoRescuX/1.0 (Android; Emergency Rescue; contact: support@georescux.org)"
        // Bounded tile storage prevents uncontrolled device cache growth.
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 100L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 80L * 1024 * 1024
        AndroidGraphicFactory.createInstance(activity.application)
    }

    @Suppress("DEPRECATION")
    fun configure(activity: Activity, mapView: MapView, region: MapRegion): Result {
        mapView.setMultiTouchControls(true)

        if (region.tileAssetPath.isNotBlank()) {
            TileArchiveInstaller.ensureExtracted(activity, region)
        }

        val osmdroidBase = File(activity.filesDir, "osmdroid")
        val expectedArchive = File(osmdroidBase, "${region.id}-tiles.sqlite")
        val expectedZip = File(osmdroidBase, "${region.id}-tiles.zip")
        val expectedMap = File(osmdroidBase, "${region.id}-tiles.map")
        val mapCandidates = listOf(
            expectedMap,
            File(File(activity.filesDir, "output/${region.id}"), "state.map"),
            File(File(activity.filesDir, "maps/${region.id}"), "state.map"),
            File(File(activity.getExternalFilesDir(null), "output/${region.id}"), "state.map"),
            File(
                File(System.getProperty("user.dir") ?: "").parentFile ?: activity.filesDir,
                "output/${region.id}",
            ).let { File(it, "state.map") },
        )
        val mapFile = mapCandidates.firstOrNull { it.exists() }

        val tier = when {
            mapFile != null -> {
                val forge = org.osmdroid.mapsforge.MapsForgeTileSource.createFromFiles(
                    arrayOf(mapFile),
                    org.mapsforge.map.rendertheme.InternalRenderTheme.OSMARENDER,
                    "RenderTheme.OSMARENDER",
                )
                mapView.tileProvider = org.osmdroid.mapsforge.MapsForgeTileProvider(
                    SimpleRegisterReceiver(activity),
                    forge,
                    null,
                )
                mapView.setUseDataConnection(false)
                Tier.VECTOR
            }

            expectedArchive.exists() || expectedZip.exists() -> {
                mapView.setUseDataConnection(false)
                mapView.setTileSource(
                    XYTileSource("${region.id}-offline", 1, 20, 256, ".png", emptyArray()),
                )
                Tier.SQLITE_ARCHIVE
            }

            else -> {
                mapView.setUseDataConnection(true)
                mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                Tier.ONLINE
            }
        }

        // State-scale regions frame the whole area; the small sample region
        // keeps its street-level default.
        val regionLatSpan = region.maxLatitude - region.minLatitude
        mapView.controller.setZoom(if (regionLatSpan > 1.0) 7.5 else 15.5)
        mapView.controller.setCenter(
            GeoPoint(
                (region.minLatitude + region.maxLatitude) / 2,
                (region.minLongitude + region.maxLongitude) / 2,
            ),
        )

        return Result(tier)
    }
}

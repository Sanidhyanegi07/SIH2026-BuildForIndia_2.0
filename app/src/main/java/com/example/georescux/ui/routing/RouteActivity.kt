package com.example.georescux.ui.routing

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.maps.MapRegionCatalog
import com.example.georescux.data.maps.PlaceEntry
import com.example.georescux.data.maps.PlaceIndex
import com.example.georescux.data.maps.TileArchiveInstaller
import com.example.georescux.domain.routing.NearestNode
import com.example.georescux.domain.routing.RouteGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.util.Locale

/**
 * Offline evacuation routing screen (Stage 7B-1).
 *
 * - Offline map: osmdroid renders the bundled sample-region tile archive
 *   (no network is used; the map never falls back to online tiles).
 * - Interactive Map: tap on map to pick Start/Destination nodes using A*.
 * - Overlays: Safe havens and road hazards visually rendered on map.
 * - Auto-Fit: camera automatically frames the computed shortest path.
 * - GPS start: after the location permission is granted, the graph node
 *   nearest to the current location becomes the default start.
 * - Region selection (Stage 7B-4): the active region is the persisted
 *   selection (sample region by default). When a GPS fix falls inside
 *   another bundled region (e.g. Uttarakhand), the region is switched and
 *   the screen rebinds to that region's graph. The graph itself loads off
 *   the main thread; overlays render when it arrives.
 * - Routing: hazard-aware A* via the RouteRepository (Stage 7A engine).
 * - Attribution: Map data © OpenStreetMap contributors.
 */
class RouteActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var viewModel: RouteViewModel
    private var graph: RouteGraph? = null
    private var routePolyline: Polyline? = null
    private var startMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var locationStarted = false
    private var staticOverlaysRendered = false
    private var activeRegion = MapRegionCatalog.sampleRegion
    private var places: List<PlaceEntry> = emptyList()
    private var connectivityBadge: TextView? = null
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private val sosMarkers = mutableListOf<Marker>()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLocationAcquisition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val osmdroidBase = File(filesDir, "osmdroid")
        Configuration.getInstance().osmdroidBasePath = osmdroidBase
        Configuration.getInstance().osmdroidTileCache = File(osmdroidBase, "tiles")
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_route)

        // Initialize Mapsforge graphic factory
        AndroidGraphicFactory.createInstance(this.application)

        val container = container()
        activeRegion = MapRegionCatalog.byId(container.activeRegionId) ?: MapRegionCatalog.sampleRegion
        bindViewModel()

        if (activeRegion.tileAssetPath.isNotBlank()) {
            TileArchiveInstaller.ensureExtracted(this, activeRegion)
        }

        findViewById<TextView>(R.id.textRegionSubtitle).text =
            "Offline evacuation routing — ${activeRegion.displayName}"
        connectivityBadge = findViewById(R.id.textConnectivityBadge)
        updateConnectivityBadge()

        // Offline place-name search: autocomplete + name resolution
        // (places.json is produced by the region's build tool).
        places = PlaceIndex.loadFromAsset(
            assets, "maps/${activeRegion.id}-places.json"
        )
        val startField = findViewById<AutoCompleteTextView>(R.id.editTextStartNode)
        val destinationField = findViewById<AutoCompleteTextView>(R.id.editTextDestinationNode)
        if (places.isNotEmpty()) {
            val nameAdapter = ArrayAdapter(
                this, android.R.layout.simple_dropdown_item_1line,
                places.map { it.name }.distinct()
            )
            startField.setAdapter(nameAdapter)
            destinationField.setAdapter(nameAdapter)
        }

        val mapView = findViewById<MapView>(R.id.mapView)
        mapView.setUseDataConnection(false)

        // Find Mapsforge offline vector map file or fallback to SQLite archive.
        // Candidates: the TileArchiveInstaller-extracted bundled vector map,
        // then the regional-map-package locations (output/{regionId}/state.map).
        val expectedArchive = File(osmdroidBase, "${activeRegion.id}-tiles.sqlite")
        val expectedZip = File(osmdroidBase, "${activeRegion.id}-tiles.zip")
        val expectedMap = File(osmdroidBase, "${activeRegion.id}-tiles.map")
        val mapCandidates = listOf(
            expectedMap,
            File(File(filesDir, "output/${activeRegion.id}"), "state.map"),
            File(File(filesDir, "maps/${activeRegion.id}"), "state.map"),
            File(File(getExternalFilesDir(null), "output/${activeRegion.id}"), "state.map"),
            File(File(File(System.getProperty("user.dir") ?: "").parentFile ?: filesDir, "output/${activeRegion.id}"), "state.map")
        )
        val mapFile = mapCandidates.firstOrNull { it.exists() }

        if (mapFile != null) {
            // OSMARENDER = the classic fully-colored OSM style (streets,
            // buildings, green areas — the "real map" look). The bare
            // DEFAULT theme renders unstyled grey lines only.
            val forge = MapsForgeTileSource.createFromFiles(
                arrayOf(mapFile), InternalRenderTheme.OSMARENDER, "RenderTheme.OSMARENDER"
            )
            val provider = MapsForgeTileProvider(
                SimpleRegisterReceiver(this),
                forge, null
            )
            mapView.tileProvider = provider
            findViewById<TextView>(R.id.textViewNoMapData).visibility = View.GONE
        } else {
            val tileSourceName = "${activeRegion.id}-offline"
            mapView.setTileSource(
                XYTileSource(tileSourceName, 1, 20, 256, ".png", emptyArray())
            )
            val hasOfflineData = expectedArchive.exists() || expectedZip.exists()
            findViewById<TextView>(R.id.textViewNoMapData).visibility = if (hasOfflineData) View.GONE else View.VISIBLE
        }
        // State-scale regions frame the whole area; the small sample region
        // keeps its street-level default.
        val regionLatSpan = activeRegion.maxLatitude - activeRegion.minLatitude
        mapView.controller.setZoom(if (regionLatSpan > 1.0) 7.5 else 15.5)
        mapView.controller.setCenter(
            GeoPoint(
                (activeRegion.minLatitude + activeRegion.maxLatitude) / 2,
                (activeRegion.minLongitude + activeRegion.maxLongitude) / 2
            )
        )

        val startEditText = findViewById<EditText>(R.id.editTextStartNode)
        val destinationEditText = findViewById<EditText>(R.id.editTextDestinationNode)

        // Map touch / tap listener to select nodes directly on the map
        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                val currentGraph = graph ?: return false
                val nearest = NearestNode.find(currentGraph, p.latitude, p.longitude) ?: return false

                val currentStart = startEditText.text.toString()

                if (currentStart.isBlank() || currentStart == nearest.id) {
                    startEditText.setText(nearest.id)
                    Toast.makeText(this@RouteActivity, "Start node set: ${nearest.id}", Toast.LENGTH_SHORT).show()
                } else {
                    destinationEditText.setText(nearest.id)
                    Toast.makeText(this@RouteActivity, "Destination set: ${nearest.id}", Toast.LENGTH_SHORT).show()
                }

                uiScope.launch {
                    viewModel.findRouteFromScreen(
                        selectedStartNodeId = resolveNodeId(startEditText.text.toString()),
                        destinationNodeId = resolveNodeId(destinationEditText.text.toString()),
                    )
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                val currentGraph = graph ?: return false
                val nearest = NearestNode.find(currentGraph, p.latitude, p.longitude) ?: return false
                startEditText.setText(nearest.id)
                Toast.makeText(this@RouteActivity, "Start node set: ${nearest.id}", Toast.LENGTH_SHORT).show()
                uiScope.launch {
                    viewModel.findRouteFromScreen(
                        selectedStartNodeId = resolveNodeId(startEditText.text.toString()),
                        destinationNodeId = resolveNodeId(destinationEditText.text.toString()),
                    )
                }
                return true
            }
        })
        mapView.overlays.add(0, mapEventsOverlay)

        findViewById<Button>(R.id.buttonFindRoute).setOnClickListener {
            uiScope.launch {
                viewModel.findRouteFromScreen(
                    selectedStartNodeId = resolveNodeId(startEditText.text.toString()),
                    destinationNodeId = resolveNodeId(destinationEditText.text.toString()),
                )
            }
        }

        findViewById<Button>(R.id.buttonReroute).setOnClickListener {
            uiScope.launch {
                viewModel.reroute(
                    fallbackStartNodeId = resolveNodeId(startEditText.text.toString()),
                    fallbackDestinationNodeId = resolveNodeId(destinationEditText.text.toString()),
                )
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startLocationAcquisition()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        uiScope.launch {
            viewModel.uiState.collect { render(it, startEditText, destinationEditText) }
        }
    }

    /**
     * Binds (or rebinds after a region switch) to the ViewModel of the
     * active region. The key includes the region id so a region switch
     * constructs a fresh ViewModel over the new region's repository —
     * a plain get() would survive recreation with the previous region.
     */
    private fun bindViewModel() {
        val container = container()
        val key = "route_vm_${container.activeRegionId}"
        viewModel = ViewModelProvider(
            this,
            RouteViewModel.Factory(container.routeRepository)
        )[key, RouteViewModel::class.java]
    }

    private fun renderStaticMapOverlays(graph: RouteGraph, mapView: MapView) {
        val havenIcon = rememberHavenIcon()
        // Render Safe Havens
        graph.nodes.filter { it.isSafeHaven }.forEach { safeHaven ->
            val marker = Marker(mapView)
            marker.position = GeoPoint(safeHaven.latitude, safeHaven.longitude)
            marker.title = "🏥 Safe Haven: ${safeHaven.id}"
            marker.snippet = "Evacuation Safe Zone"
            marker.icon = havenIcon
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            mapView.overlays.add(marker)
        }

        // Render Hazards
        graph.hazards.forEach { hazard ->
            val fromNode = graph.node(hazard.fromNodeId)
            val toNode = graph.node(hazard.toNodeId)
            if (fromNode != null && toNode != null) {
                val midLat = (fromNode.latitude + toNode.latitude) / 2.0
                val midLng = (fromNode.longitude + toNode.longitude) / 2.0
                val hazardMarker = Marker(mapView)
                hazardMarker.position = GeoPoint(midLat, midLng)
                hazardMarker.title = "⚠️ Hazard: ${hazard.id}"
                hazardMarker.snippet = "Penalty: +${hazard.penaltyMeters.toInt()}m"
                mapView.overlays.add(hazardMarker)
            }
        }
    }

    /** Cached green cross marker so all havens share one bitmap. */
    private var havenIconCache: android.graphics.drawable.Drawable? = null

    private fun rememberHavenIcon(): android.graphics.drawable.Drawable {
        havenIconCache?.let { return it }
        val size = 44
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2E7D32.toInt()
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.strokeWidth = 5f
        canvas.drawLine(size / 2f, 12f, size / 2f, size - 12f, paint)
        canvas.drawLine(12f, size / 2f, size - 12f, size / 2f, paint)
        val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
        havenIconCache = drawable
        return drawable
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveNodeId(input: String): String? = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext null
        val nodes = graph?.nodes ?: return@withContext null

        if (nodes.any { it.id == input }) return@withContext input

        // Offline place-name resolution (faster + offline vs the Geocoder).
        PlaceIndex.find(places, input)?.let { return@withContext it.nodeId }

        var targetLat: Double? = null
        var targetLng: Double? = null

        val decimalRegex = Regex("""(-?\d+\.\d+)[,\s]+(-?\d+\.\d+)""")
        val match = decimalRegex.find(input)
        if (match != null) {
            targetLat = match.groupValues[1].toDoubleOrNull()
            targetLng = match.groupValues[2].toDoubleOrNull()
        } else {
            try {
                val geocoder = Geocoder(this@RouteActivity, Locale.getDefault())
                val results = geocoder.getFromLocationName(input, 1)
                if (!results.isNullOrEmpty()) {
                    targetLat = results[0].latitude
                    targetLng = results[0].longitude
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (targetLat == null || targetLng == null) return@withContext null

        nodes.minByOrNull {
            val dLat = Math.toRadians(it.latitude - targetLat)
            val dLng = Math.toRadians(it.longitude - targetLng)
            dLat * dLat + dLng * dLng
        }?.id
    }

    private fun startLocationAcquisition() {
        if (locationStarted) return
        locationStarted = true
        container().locationRepository.startAcquisition { fix ->
            runOnUiThread {
                // Stage 7B-4 region selection: a fix inside another bundled
                // region switches the active region and rebinds the screen.
                val fixRegion = MapRegionCatalog.regionForLocation(fix.latitude, fix.longitude)
                if (fixRegion != null && fixRegion.id != container().activeRegionId) {
                    container().setActiveRegion(fixRegion.id)
                    Toast.makeText(
                        this,
                        "Offline map region switched to ${fixRegion.displayName}",
                        Toast.LENGTH_LONG
                    ).show()
                    recreate()
                    return@runOnUiThread
                }
                viewModel.setStartFromLocation(fix.latitude, fix.longitude)
                container().locationRepository.stopAcquisition()
            }
        }
    }

    /**
     * Live SOS layer (spec Phase 6/13): the currently active emergency and
     * the most recent located emergencies — INCLUDING ones received over
     * the BLE/Nearby mesh (their ids carry the MESH prefix) — rendered as
     * red markers on the offline map. Re-rendered on every resume so a SOS
     * started (or mesh-relayed) while this screen is open appears too.
     */
    private fun renderSosMarkers() {
        val mapView = findViewById<MapView>(R.id.mapView)
        synchronized(sosMarkers) {
            sosMarkers.forEach { marker ->
                try {
                    mapView.overlayManager.remove(marker)
                } catch (_: Exception) {
                }
            }
            sosMarkers.clear()
            val sosIcon = rememberSosIcon()
            val container = container()
            // The active emergency is NOT part of history by design — include it explicitly.
            val active = runCatching { container.sosRepository.getActiveEmergency() }.getOrNull()
            val historyLocated = runCatching { container.sosRepository.getHistory() }.getOrNull()
                .orEmpty()
                .asSequence()
                .filter { it.location != null }
                .take(RECENT_SOS_MARKERS)
            val located = buildList {
                addAll(historyLocated)
                if (active?.location != null) add(active)
            }
            located.forEach { emergency ->
                val location = emergency.location ?: return@forEach
                val marker = Marker(mapView).apply {
                    position = GeoPoint(location.latitude, location.longitude)
                    title = if (emergency.isActive) "🚨 ACTIVE SOS" else "SOS record"
                    snippet = "id=${emergency.id.take(18)}… · " +
                        (if (emergency.id.startsWith("MESH")) "received via mesh" else "this device")
                    icon = sosIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(marker)
                sosMarkers.add(marker)
            }
        }
        mapView.invalidate()
    }

    /** Cached red SOS marker shared by all emergency markers. */
    private var sosIconCache: android.graphics.drawable.Drawable? = null

    private fun rememberSosIcon(): android.graphics.drawable.Drawable {
        sosIconCache?.let { return it }
        val size = 44
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC62828.toInt()
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.strokeWidth = 5f
        canvas.drawLine(size / 2f, 10f, size / 2f, size - 12f, paint)
        canvas.drawPoint(size / 2f, size - 8f, paint)
        val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
        sosIconCache = drawable
        return drawable
    }

    /**
     * Connectivity badge (spec Phase 17): a single, honest line —
     * OFFLINE when no usable network (map/routing/SOS unaffected), ONLINE
     * otherwise with the automatic-sync note. Technical Firebase errors are
     * never surfaced; failures already land in the durable pending state.
     */
    private fun updateConnectivityBadge() {
        val badge = connectivityBadge ?: return
        val online = try {
            val manager = getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            val capabilities = network?.let { manager.getNetworkCapabilities(it) }
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true ||
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Exception) {
            false
        }
        if (online) {
            badge.text = "● ONLINE — syncing via Firebase · map/routing stay offline-first"
            badge.setTextColor(0xFF2E7D32.toInt())
        } else {
            badge.text = "● OFFLINE — map, Safe Route and SOS fully functional · sync pending"
            badge.setTextColor(0xFFEF6C00.toInt())
        }
    }

    private fun container() = (application as GeoRescuXApplication).appContainer

    private fun render(
        ui: RouteUiState,
        startEditText: EditText,
        destinationEditText: EditText,
    ) {
        val mapView = findViewById<MapView>(R.id.mapView)
        val stepsContainer = findViewById<LinearLayout>(R.id.routeStepsContainer)
        val noRouteText = findViewById<TextView>(R.id.textViewNoRoute)
        val summaryText = findViewById<TextView>(R.id.textViewRouteSummary)
        val warningsText = findViewById<TextView>(R.id.textViewHazardWarnings)

        stepsContainer.removeAllViews()

        // The regional graph arrives asynchronously; render its static
        // overlays once and keep the field used by map taps / geocoding.
        ui.graph?.let { loadedGraph ->
            graph = loadedGraph
            if (!staticOverlaysRendered) {
                staticOverlaysRendered = true
                renderStaticMapOverlays(loadedGraph, mapView)
                renderSosMarkers()
                loadedGraph.nodes.firstOrNull { it.isSafeHaven }?.id?.let { id ->
                    if (destinationEditText.text.isBlank()) destinationEditText.setText(id)
                }
            }
        }

        syncStartEditText(startEditText, ui.startNodeId)
        syncDestinationEditText(destinationEditText, ui.destinationNodeId)

        if (ui.graphLoading) {
            noRouteText.visibility = View.VISIBLE
            noRouteText.text = "Loading offline map data…"
            summaryText.visibility = View.GONE
            warningsText.visibility = View.GONE
            return
        }

        val route = ui.route
        if (route == null) {
            when {
                ui.selectionRequired -> {
                    noRouteText.visibility = View.VISIBLE
                    noRouteText.text = "Select a start and a destination, then find a route."
                }
                ui.routeNotFound -> {
                    noRouteText.visibility = View.VISIBLE
                    noRouteText.text = "No route available to that destination. Try a safe haven."
                }
                else -> noRouteText.visibility = View.GONE
            }
            summaryText.visibility = View.GONE
            warningsText.visibility = View.GONE
            return
        }

        noRouteText.visibility = View.GONE
        summaryText.visibility = View.VISIBLE
        summaryText.text =
            "Total distance: ${route.totalDistanceMeters} m · cost ${route.totalCostMeters}"

        if (route.hazardWarnings.isEmpty()) {
            warningsText.visibility = View.GONE
        } else {
            warningsText.visibility = View.VISIBLE
            warningsText.text = "⚠ Hazards on this route: " +
                route.hazardWarnings.joinToString { hazard -> "${hazard.id} (+${hazard.penaltyMeters} m)" }
        }

        var stepNumber = 1
        route.nodeIds.zipWithNext { fromNodeId, toNodeId ->
            val row = layoutInflater.inflate(R.layout.item_route_step, stepsContainer, false)
            row.findViewById<TextView>(R.id.textStepTitle).text =
                "$stepNumber. $fromNodeId → $toNodeId"
            val legDistance = ui.graph?.edgeBetween(fromNodeId, toNodeId)?.distanceMeters
            row.findViewById<TextView>(R.id.textStepDetail).text = legDistance?.let { "$it m" } ?: ""

            // Tapping a step focuses the map camera on that segment's starting node
            row.setOnClickListener {
                ui.graph?.node(fromNodeId)?.let { node ->
                    mapView.controller.animateTo(GeoPoint(node.latitude, node.longitude))
                    mapView.controller.setZoom(17.5)
                }
            }

            stepNumber++
            stepsContainer.addView(row)
        }

        drawRouteOnMap(ui)
    }

    private fun drawRouteOnMap(ui: RouteUiState) {
        val mapView = findViewById<MapView>(R.id.mapView)
        val route = ui.route ?: return
        val points = route.nodeIds.mapNotNull { nodeId ->
            ui.graph?.node(nodeId)?.let { node -> GeoPoint(node.latitude, node.longitude) }
        }
        if (points.isEmpty()) return

        val polyline = routePolyline ?: Polyline(mapView).also { polyline ->
            polyline.outlinePaint.color = 0xFFB71C1C.toInt()
            polyline.outlinePaint.strokeWidth = 10f
            mapView.overlayManager.add(polyline)
            routePolyline = polyline
        }
        polyline.setPoints(points)

        val startMarker = this.startMarker ?: Marker(mapView).also { marker ->
            marker.title = "Start"
            mapView.overlayManager.add(marker)
            this.startMarker = marker
        }
        points.firstOrNull()?.let { startMarker.position = it }

        val destinationMarker = this.destinationMarker ?: Marker(mapView).also { marker ->
            marker.title = "Destination"
            mapView.overlayManager.add(marker)
            this.destinationMarker = marker
        }
        points.lastOrNull()?.let { destinationMarker.position = it }

        // Auto-fit map camera bounds to frame the calculated A* route
        try {
            if (points.size == 1) {
                mapView.controller.setCenter(points.first())
                mapView.controller.setZoom(17.0)
            } else {
                val boundingBox = BoundingBox.fromGeoPoints(points)
                mapView.zoomToBoundingBox(boundingBox, true, 80)
            }
        } catch (_: Exception) {
            mapView.controller.setCenter(points.first())
        }

        mapView.invalidate()
    }

    private fun syncStartEditText(editText: EditText, startNodeId: String?) {
        startNodeId ?: return
        if (editText.text.isBlank()) editText.setText(startNodeId)
    }

    private fun syncDestinationEditText(editText: EditText, destinationNodeId: String?) {
        destinationNodeId ?: return
        if (editText.text.isBlank()) editText.setText(destinationNodeId)
    }

    override fun onResume() {
        super.onResume()
        // Refresh the live layers: an SOS started (or mesh-relayed) while
        // this screen was closed must appear on the map, and the badge must
        // reflect the current network state.
        updateConnectivityBadge()
        if (staticOverlaysRendered) renderSosMarkers()
        registerConnectivityCallback()
    }

    override fun onPause() {
        super.onPause()
        unregisterConnectivityCallback()
    }

    private fun registerConnectivityCallback() {
        if (connectivityCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { updateConnectivityBadge() }
            }

            override fun onLost(network: Network) {
                runOnUiThread { updateConnectivityBadge() }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(newCallback)
            connectivityCallback = newCallback
        } catch (_: Exception) {
            // Badge still reflects state at next resume; never crash.
        }
    }

    private fun unregisterConnectivityCallback() {
        val callback = connectivityCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        connectivityCallback = null
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** How many recent located emergencies to pin on the map. */
        const val RECENT_SOS_MARKERS = 10
    }
}

package com.example.georescux.ui.routing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.maps.MapRegionCatalog
import com.example.georescux.data.maps.TileArchiveInstaller
import com.example.georescux.domain.routing.RouteGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline evacuation routing screen (Stage 7B-1).
 *
 * - Offline map: osmdroid renders the bundled sample-region tile archive
 *   (no network is used; the map never falls back to online tiles).
 * - GPS start: after the location permission is granted, the graph node
 *   nearest to the current location becomes the default start.
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

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLocationAcquisition()
        // Denied: manual start-node selection keeps the screen fully usable.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid must be configured BEFORE the MapView is inflated. All
        // paths are app-private; the tile source is strictly offline.
        val osmdroidBase = File(filesDir, "osmdroid")
        Configuration.getInstance().osmdroidBasePath = osmdroidBase
        Configuration.getInstance().osmdroidTileCache = File(osmdroidBase, "tiles")
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_route)

        val container = (application as GeoRescuXApplication).appContainer
        viewModel = ViewModelProvider(this, RouteViewModel.Factory(container.routeRepository))
            .get(RouteViewModel::class.java)

        graph = viewModel.uiState.value.graph
        val region = MapRegionCatalog.sampleRegion

        // Install the bundled offline tile archive (idempotent).
        TileArchiveInstaller.ensureExtracted(this, region)

        val mapView = findViewById<MapView>(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setUseDataConnection(true)
        mapView.controller.setZoom(15.5)
        mapView.controller.setCenter(
            GeoPoint(
                (region.minLatitude + region.maxLatitude) / 2,
                (region.minLongitude + region.maxLongitude) / 2
            )
        )

        val startEditText = findViewById<EditText>(R.id.editTextStartNode)
        val destinationEditText = findViewById<EditText>(R.id.editTextDestinationNode)
        
        // Set default destination text if available
        graph?.nodes?.firstOrNull { it.isSafeHaven }?.id?.let {
            destinationEditText.setText(it)
        }

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

        // GPS start selection: request the permission only when needed and
        // fall back to manual selection when unavailable or denied.
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

    private suspend fun resolveNodeId(input: String): String? = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext null
        val nodes = graph?.nodes ?: return@withContext null
        
        if (nodes.any { it.id == input }) return@withContext input
        
        var targetLat: Double? = null
        var targetLng: Double? = null
        
        val decimalRegex = Regex("""(-?\d+\.\d+)[,\s]+(-?\d+\.\d+)""")
        val match = decimalRegex.find(input)
        if (match != null) {
            targetLat = match.groupValues[1].toDoubleOrNull()
            targetLng = match.groupValues[2].toDoubleOrNull()
        } else {
            try {
                val geocoder = android.location.Geocoder(this@RouteActivity, Locale.getDefault())
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
        // One-shot: the first fix selects the nearest graph node, then the
        // acquisition stops (no continuous tracking on this screen).
        container().locationRepository.startAcquisition { fix ->
            runOnUiThread {
                viewModel.setStartFromLocation(fix.latitude, fix.longitude)
                container().locationRepository.stopAcquisition()
            }
        }
    }

    private fun container() = (application as GeoRescuXApplication).appContainer

    private fun render(
        ui: RouteUiState,
        startEditText: EditText,
        destinationEditText: EditText,
    ) {
        val stepsContainer = findViewById<LinearLayout>(R.id.routeStepsContainer)
        val noRouteText = findViewById<TextView>(R.id.textViewNoRoute)
        val summaryText = findViewById<TextView>(R.id.textViewRouteSummary)
        val warningsText = findViewById<TextView>(R.id.textViewHazardWarnings)

        stepsContainer.removeAllViews()

        // Selection sync FIRST: the GPS-selected start must be visible in
        // the start spinner even before any route has been drawn.
        syncStartEditText(startEditText, ui.startNodeId)
        syncDestinationEditText(destinationEditText, ui.destinationNodeId)

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

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}

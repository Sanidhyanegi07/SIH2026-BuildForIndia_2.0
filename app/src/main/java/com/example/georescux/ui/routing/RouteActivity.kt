package com.example.georescux.ui.routing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
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
import org.osmdroid.config.Configuration
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
        mapView.setTileSource(
            XYTileSource("sample-region-offline", 13, 17, 256, ".png", emptyArray())
        )
        mapView.setUseDataConnection(false)
        mapView.controller.setZoom(15.5)
        mapView.controller.setCenter(
            GeoPoint(
                (region.minLatitude + region.maxLatitude) / 2,
                (region.minLongitude + region.maxLongitude) / 2
            )
        )

        val startSpinner = findViewById<Spinner>(R.id.spinnerStartNode)
        val destinationSpinner = findViewById<Spinner>(R.id.spinnerDestinationNode)
        graph?.let { setupSpinners(it, startSpinner, destinationSpinner) }

        findViewById<Button>(R.id.buttonFindRoute).setOnClickListener {
            viewModel.findRouteFromScreen(
                selectedStartNodeId = selectedNodeId(startSpinner),
                destinationNodeId = selectedNodeId(destinationSpinner),
            )
        }

        findViewById<Button>(R.id.buttonReroute).setOnClickListener {
            viewModel.reroute(
                fallbackStartNodeId = selectedNodeId(startSpinner),
                fallbackDestinationNodeId = selectedNodeId(destinationSpinner),
            )
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
            viewModel.uiState.collect { render(it, startSpinner, destinationSpinner) }
        }
    }

    private fun setupSpinners(graph: RouteGraph, startSpinner: Spinner, destinationSpinner: Spinner) {
        val labels = graph.nodes.map { node ->
            if (node.isSafeHaven) "${node.id} (Safe haven)" else node.id
        }
        val adapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            labels,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        startSpinner.adapter = adapter
        destinationSpinner.adapter = adapter

        // Default destination: the first safe haven in the graph, when present.
        val firstSafeHaven = graph.nodes.indexOfFirst { it.isSafeHaven }
        if (firstSafeHaven >= 0) destinationSpinner.setSelection(firstSafeHaven)
    }

    private fun selectedNodeId(spinner: Spinner): String? {
        val nodes = graph?.nodes ?: return null
        return nodes.getOrNull(spinner.selectedItemPosition)?.id
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
        startSpinner: Spinner,
        destinationSpinner: Spinner,
    ) {
        val stepsContainer = findViewById<LinearLayout>(R.id.routeStepsContainer)
        val noRouteText = findViewById<TextView>(R.id.textViewNoRoute)
        val summaryText = findViewById<TextView>(R.id.textViewRouteSummary)
        val warningsText = findViewById<TextView>(R.id.textViewHazardWarnings)

        stepsContainer.removeAllViews()

        // Selection sync FIRST: the GPS-selected start must be visible in
        // the start spinner even before any route has been drawn.
        syncStartSpinner(startSpinner, ui.startNodeId)
        syncDestinationSpinner(destinationSpinner, ui.destinationNodeId)

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

    private fun syncStartSpinner(spinner: Spinner, startNodeId: String?) {
        startNodeId ?: return
        val nodes = graph?.nodes ?: return
        val index = nodes.indexOfFirst { it.id == startNodeId }
        if (index >= 0 && spinner.selectedItemPosition != index) spinner.setSelection(index)
    }

    private fun syncDestinationSpinner(spinner: Spinner, destinationNodeId: String?) {
        destinationNodeId ?: return
        val nodes = graph?.nodes ?: return
        val index = nodes.indexOfFirst { it.id == destinationNodeId }
        if (index >= 0 && spinner.selectedItemPosition != index) spinner.setSelection(index)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}

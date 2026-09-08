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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.maps.MapRegionCatalog
import com.example.georescux.data.maps.TileArchiveInstaller
import com.example.georescux.domain.routing.NearestNode
import com.example.georescux.domain.routing.RouteGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        graph?.let {
            setupSpinners(it, startSpinner, destinationSpinner)
            renderStaticMapOverlays(it, mapView)
        }

        // Map touch / tap listener to select nodes directly on the map
        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                val currentGraph = graph ?: return false
                val nearest = NearestNode.find(currentGraph, p.latitude, p.longitude) ?: return false

                val currentStart = selectedNodeId(startSpinner)

                if (currentStart == null || currentStart == nearest.id) {
                    setSpinnerSelection(startSpinner, nearest.id)
                    Toast.makeText(this@RouteActivity, "Start node set: ${nearest.id}", Toast.LENGTH_SHORT).show()
                } else {
                    setSpinnerSelection(destinationSpinner, nearest.id)
                    Toast.makeText(this@RouteActivity, "Destination set: ${nearest.id}", Toast.LENGTH_SHORT).show()
                }

                viewModel.findRouteFromScreen(
                    selectedStartNodeId = selectedNodeId(startSpinner),
                    destinationNodeId = selectedNodeId(destinationSpinner),
                )
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                val currentGraph = graph ?: return false
                val nearest = NearestNode.find(currentGraph, p.latitude, p.longitude) ?: return false
                setSpinnerSelection(startSpinner, nearest.id)
                Toast.makeText(this@RouteActivity, "Start node set: ${nearest.id}", Toast.LENGTH_SHORT).show()
                viewModel.findRouteFromScreen(
                    selectedStartNodeId = selectedNodeId(startSpinner),
                    destinationNodeId = selectedNodeId(destinationSpinner),
                )
                return true
            }
        })
        mapView.overlays.add(0, mapEventsOverlay)

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

    private fun renderStaticMapOverlays(graph: RouteGraph, mapView: MapView) {
        // Render Safe Havens
        graph.nodes.filter { it.isSafeHaven }.forEach { safeHaven ->
            val marker = Marker(mapView)
            marker.position = GeoPoint(safeHaven.latitude, safeHaven.longitude)
            marker.title = "🏥 Safe Haven: ${safeHaven.id}"
            marker.snippet = "Evacuation Safe Zone"
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

        val firstSafeHaven = graph.nodes.indexOfFirst { it.isSafeHaven }
        if (firstSafeHaven >= 0) destinationSpinner.setSelection(firstSafeHaven)
    }

    private fun setSpinnerSelection(spinner: Spinner, nodeId: String) {
        val nodes = graph?.nodes ?: return
        val index = nodes.indexOfFirst { it.id == nodeId }
        if (index >= 0) spinner.setSelection(index)
    }

    private fun selectedNodeId(spinner: Spinner): String? {
        val nodes = graph?.nodes ?: return null
        return nodes.getOrNull(spinner.selectedItemPosition)?.id
    }

    private fun startLocationAcquisition() {
        if (locationStarted) return
        locationStarted = true
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
        val mapView = findViewById<MapView>(R.id.mapView)
        val stepsContainer = findViewById<LinearLayout>(R.id.routeStepsContainer)
        val noRouteText = findViewById<TextView>(R.id.textViewNoRoute)
        val summaryText = findViewById<TextView>(R.id.textViewRouteSummary)
        val warningsText = findViewById<TextView>(R.id.textViewHazardWarnings)

        stepsContainer.removeAllViews()

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

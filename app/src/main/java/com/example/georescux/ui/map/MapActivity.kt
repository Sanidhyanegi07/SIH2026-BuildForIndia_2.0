package com.example.georescux.ui.map

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.maps.MapRegionCatalog
import com.example.georescux.domain.routing.MapRegion
import com.example.georescux.ui.common.BottomNav
import com.example.georescux.ui.common.ConnectivityBadge
import com.example.georescux.ui.common.HelpLauncher
import com.example.georescux.ui.common.OfflineMapSetup
import com.example.georescux.ui.routing.RouteActivity
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * The MAP tab (spec §14): a modern, offline-first map view.
 *
 * Deliberately separate from Safe Route's routing workflow — this screen is
 * about orientation: pan, zoom, see the current location, the region
 * boundary and the safe havens, then jump into Safe Route to compute a
 * route. Rendering data comes from the same regional package so the map
 * stays usable with no connection wherever that data is installed.
 */
class MapActivity : AppCompatActivity() {

    private val container by lazy { (application as GeoRescuXApplication).appContainer }
    private lateinit var activeRegion: MapRegion
    private lateinit var mapView: MapView
    private lateinit var connectivityBadge: ConnectivityBadge
    private var locationMarker: Marker? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startLocationAcquisition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        activeRegion = MapRegionCatalog.byId(container.activeRegionId)
            ?: MapRegionCatalog.sampleRegion

        OfflineMapSetup.prepareOsmdroid(this)
        setContentView(R.layout.activity_map)

        findViewById<TextView>(R.id.topBarTitle).setText(R.string.map_title)
        findViewById<TextView>(R.id.textMapRegion).text =
            "${getString(R.string.map_region_label)} ${activeRegion.displayName}"

        mapView = findViewById(R.id.mapView)
        connectivityBadge = ConnectivityBadge(this)

        val result = OfflineMapSetup.configure(this, mapView, activeRegion)
        findViewById<TextView>(R.id.textMapCacheState).text = result.tier.badgeText

        // Honest coverage notice: routing data may be installed without any
        // offline rendering package. Say so instead of implying the map works
        // offline when it does not.
        val offlineMapInstalled = MapRegionCatalog.isOfflineMapInstalled(assets, activeRegion)
        findViewById<TextView>(R.id.textNoOfflineMap).visibility =
            if (offlineMapInstalled) View.GONE else View.VISIBLE

        setupRegionBoundaryOverlay()
        setupFloatingControls()
        bindConnectionBadge()
        wireHelpAndNavigation()

        if (hasLocationPermission()) {
            startLocationAcquisition()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun bindConnectionBadge() {
        connectivityBadge.bind(findViewById(R.id.textMapConnectivity))
    }

    private fun setupFloatingControls() {
        findViewById<View>(R.id.buttonMyLocation).setOnClickListener {
            val position = locationMarker?.position
            if (position != null) {
                mapView.controller.animateTo(position)
                mapView.controller.setZoom(17.0)
            } else {
                android.widget.Toast.makeText(this, "Acquiring GPS fix...", android.widget.Toast.LENGTH_SHORT).show()
                startLocationAcquisition()
            }
        }
        findViewById<View>(R.id.buttonZoomIn).setOnClickListener { mapView.controller.zoomIn() }
        findViewById<View>(R.id.buttonZoomOut).setOnClickListener { mapView.controller.zoomOut() }
    }

    /**
     * Draws the active region's boundary so the user can always see the edge
     * of the installed offline package (spec §14 "regional boundaries").
     */
    private fun setupRegionBoundaryOverlay() {
        val boundary = Polyline().apply {
            outlinePaint.color = ContextCompat.getColor(this@MapActivity, R.color.violet_light)
            outlinePaint.strokeWidth = 4f
        }
        val (minLat, maxLat) = activeRegion.minLatitude to activeRegion.maxLatitude
        val (minLng, maxLng) = activeRegion.minLongitude to activeRegion.maxLongitude
        boundary.setPoints(
            listOf(
                GeoPoint(minLat, minLng),
                GeoPoint(maxLat, minLng),
                GeoPoint(maxLat, maxLng),
                GeoPoint(minLat, maxLng),
                GeoPoint(minLat, minLng),
            ),
        )
        mapView.overlays.add(boundary)
    }

    private fun startLocationAcquisition() {
        container.locationRepository.startAcquisition { fix ->
            runOnUiThread { showLocationMarker(GeoPoint(fix.latitude, fix.longitude)) }
        }
    }

    private fun showLocationMarker(point: GeoPoint) {
        if (locationMarker == null) {
            locationMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = getString(R.string.map_my_location)
            }
            mapView.overlays.add(locationMarker)
        }
        locationMarker?.position = point
        mapView.invalidate()
    }

    private fun wireHelpAndNavigation() {
        HelpLauncher.bind(this)
        findViewById<View>(R.id.buttonOpenSafeRoute).setOnClickListener {
            startActivity(Intent(this, RouteActivity::class.java))
        }
        BottomNav.bind(this, R.id.nav_map)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        container.locationRepository.stopAcquisition()
        connectivityBadge.unbind()
        mapView.onDetach()
        super.onDestroy()
    }
}

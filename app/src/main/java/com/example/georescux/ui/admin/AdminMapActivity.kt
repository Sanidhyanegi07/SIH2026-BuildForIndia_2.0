package com.example.georescux.ui.admin

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.maps.MapRegionCatalog
import com.example.georescux.domain.admin.DistrictCatalog
import com.example.georescux.domain.admin.EmergencyPriorityCalculator
import com.example.georescux.ui.common.OfflineMapSetup
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * The administrative geographic intelligence view (spec §42/§43).
 *
 * A map of the currently scoped state with district-level drill-down: the
 * admin picks a state, then a district, and the map re-frames and shows the
 * SOS activity inside that scope together with a per-district metrics card
 * (§44). Markers come straight from the Firebase SOS node; nothing is
 * fabricated — an empty scope shows an empty map.
 *
 * This deliberately reuses the same offline tile strategy as the user map so
 * the admin portal renders identical geography.
 */
class AdminMapActivity : AppCompatActivity() {

    private val database by lazy { FirebaseDatabase.getInstance() }
    private val container by lazy { (application as GeoRescuXApplication).appContainer }
    private lateinit var mapView: MapView
    private val sosMarkers = mutableListOf<Marker>()

    private val emergencies = mutableListOf<AdminSosPoint>()

    private data class AdminSosPoint(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val isActive: Boolean,
        val isVerified: Boolean,
    ) : EmergencyVerifiable

    private var listener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OfflineMapSetup.prepareOsmdroid(this)
        setContentView(R.layout.activity_admin_map)

        mapView = findViewById(R.id.adminMapView)
        mapView.setMultiTouchControls(true)

        val stateSpinner = findViewById<Spinner>(R.id.spinnerAdminMapState)
        val districtSpinner = findViewById<Spinner>(R.id.spinnerAdminMapDistrict)
        val metricsContainer = findViewById<LinearLayout>(R.id.layoutDistrictMetrics)

        val states = MapRegionCatalog.availableRegions
        stateSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, states.map { it.displayName }
        )

        stateSpinner.onItemSelectedListener = object : AdapterViewListener {
            override fun onItemSelected(position: Int) {
                val region = states[position]
                populateDistricts(districtSpinner, region.id)
                frameRegion(region.minLatitude, region.maxLatitude,
                    region.minLongitude, region.maxLongitude)
            }
        }.toListener()

        districtSpinner.onItemSelectedListener = object : AdapterViewListener {
            override fun onItemSelected(position: Int) {
                renderMetrics(metricsContainer, districtSpinner.selectedItemPosition, states, stateSpinner.selectedItemPosition)
                renderMarkers()
            }
        }.toListener()

        findViewById<Button>(R.id.buttonAdminMapBack).setOnClickListener { finish() }

        attachSosListener()
    }

    private fun populateDistricts(spinner: Spinner, stateId: String) {
        val districts = DistrictCatalog.districtsForState(stateId)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("All districts") + districts.map { it.displayName }
        )
    }

    /** Frames the map onto a lat/lng box. */
    private fun frameRegion(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) {
        mapView.controller.setZoom(7.5)
        mapView.controller.setCenter(GeoPoint((minLat + maxLat) / 2, (minLng + maxLng) / 2))
    }

    private fun attachSosListener() {
        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                emergencies.clear()
                for (userSnap in snapshot.children) {
                    val isVerified = userSnap.child("isVerified").getValue(Boolean::class.java) ?: false
                    for (alertSnap in userSnap.children) {
                        if (alertSnap.key == "isVerified") continue
                        val loc = alertSnap.child("location")
                        val lat = loc.child("latitude").getValue(Double::class.java) ?: continue
                        val lng = loc.child("longitude").getValue(Double::class.java) ?: continue
                        val stopped = alertSnap.child("stoppedAtMs").getValue(Long::class.java)
                        emergencies.add(AdminSosPoint(
                            id = alertSnap.key ?: "alert",
                            latitude = lat, longitude = lng,
                            isActive = stopped == null,
                            isVerified = isVerified,
                        ))
                    }
                }
                renderMarkers()
                renderMetrics(findViewById(R.id.layoutDistrictMetrics),
                    findViewById<Spinner>(R.id.spinnerAdminMapDistrict).selectedItemPosition,
                    MapRegionCatalog.availableRegions,
                    findViewById<Spinner>(R.id.spinnerAdminMapState).selectedItemPosition)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        database.getReference("sos_alerts").addValueEventListener(listener!!)
    }

    /** Draws only the SOS points that fall inside the selected scope. */
    private fun renderMarkers() {
        sosMarkers.forEach { mapView.overlayManager.remove(it) }
        sosMarkers.clear()
        val scoped = scopedEmergencies()
        scoped.forEach { point ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(point.latitude, point.longitude)
                title = if (point.isActive) "🚨 ACTIVE SOS" else "SOS record"
                snippet = if (point.isVerified) "Verified user (×2 priority)" else "Unverified user (×1 priority)"
                icon = ContextCompat.getDrawable(this@AdminMapActivity,
                    if (point.isActive) android.R.drawable.ic_menu_mylocation
                    else android.R.drawable.ic_menu_recent_history)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(marker)
            sosMarkers.add(marker)
        }
        mapView.invalidate()
    }

    /** §44: the metrics card for the currently selected district scope. */
    private fun renderMetrics(
        container: LinearLayout,
        districtPosition: Int,
        states: List<com.example.georescux.domain.routing.MapRegion>,
        statePosition: Int,
    ) {
        container.removeAllViews()
        val stateId = states.getOrNull(statePosition)?.id ?: return
        val districts = DistrictCatalog.districtsForState(stateId)
        val districtName = if (districtPosition > 0) {
            districts.getOrNull(districtPosition - 1)?.displayName
        } else null

        val scoped = scopedEmergencies()
        val stats = EmergencyPriorityCalculator.calculate(scoped)

        val title = if (districtName != null) districtName else states[statePosition].displayName
        container.addView(metricRow("Region", title))
        container.addView(metricRow("Total SOS (raw count = ground truth)", stats.totalSosCount.toString()))
        container.addView(metricRow("Verified-user SOS", stats.verifiedUserSosCount.toString()))
        container.addView(metricRow("Unverified-user SOS", stats.unverifiedUserSosCount.toString()))
        container.addView(metricRow("Emergency Priority Score", stats.emergencyPriorityScore.toString()))
        container.addView(metricRow("Active in scope", scoped.count { it.isActive }.toString()))
        container.addView(TextView(this).apply {
            text = "Note: the priority score is an internal triage heuristic, not a severity measurement."
            setTextColor(ContextCompat.getColor(this@AdminMapActivity, R.color.text_secondary))
            textSize = 10f
            setPadding(0, 8, 0, 0)
        })
    }

    /** Filters emergencies to the selected state (and district when one is chosen). */
    private fun scopedEmergencies(): List<AdminSosPoint> {
        val stateSpinner = findViewById<Spinner>(R.id.spinnerAdminMapState)
        val districtSpinner = findViewById<Spinner>(R.id.spinnerAdminMapDistrict)
        val states = MapRegionCatalog.availableRegions
        val state = states.getOrNull(stateSpinner.selectedItemPosition) ?: return emptyList()
        val districts = DistrictCatalog.districtsForState(state.id)
        val district = if (districtSpinner.selectedItemPosition > 0)
            districts.getOrNull(districtSpinner.selectedItemPosition - 1) else null

        return emergencies.filter { point ->
            val inState = point.latitude in state.minLatitude..state.maxLatitude &&
                point.longitude in state.minLongitude..state.maxLongitude
            if (!inState) return@filter false
            if (district != null) district.contains(point.latitude, point.longitude) else true
        }
    }

    private fun metricRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
        }
        row.addView(TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(ContextCompat.getColor(this@AdminMapActivity, R.color.text_secondary))
            textSize = 12f
        })
        row.addView(TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@AdminMapActivity, R.color.text_primary))
            textSize = 13f
            textStyle = android.graphics.Typeface.BOLD
        })
        return row
    }

    override fun onDestroy() {
        listener?.let { database.getReference("sos_alerts").removeEventListener(it) }
        mapView.onDetach()
        super.onDestroy()
    }

    /** Small helper to keep the spinner boilerplate readable. */
    private interface AdapterViewListener {
        fun onItemSelected(position: Int)
    }

    private fun AdapterViewListener.toListener() = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) =
            this@toListener.onItemSelected(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }
}

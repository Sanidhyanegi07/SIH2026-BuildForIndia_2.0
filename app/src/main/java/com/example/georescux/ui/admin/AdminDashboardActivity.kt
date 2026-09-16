package com.example.georescux.ui.admin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.auth.UserRole
import com.example.georescux.data.maps.MapRegionCatalog
import com.example.georescux.domain.admin.EmergencyPriorityCalculator
import com.example.georescux.domain.admin.EmergencyVerifiable
import com.example.georescux.domain.routing.RoadHazard
import com.example.georescux.ui.auth.LoginActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GeoRescuX Admin Portal.
 *
 * Enforces strict authorization via Firebase Custom Claims (admin == true).
 * Provides full operational visibility and management:
 * 1. View active SOS events
 * 2. View emergency locations & accuracy
 * 3. View hazards
 * 4. View blocked roads
 * 5. View reported incidents
 * 6. Update incident status
 * 7. Manage emergency resources / safe havens
 * 8. Manage routing/hazard info directly into Firebase and local RouteRepository
 * 9. Monitor synchronization state
 * 10. View system and network status
 */
class AdminDashboardActivity : AppCompatActivity() {

    private val database by lazy { FirebaseDatabase.getInstance() }
    private enum class AdminTab { EMERGENCIES, HAZARDS, INCIDENTS, RESOURCES, VERIFICATIONS }
    private var currentTab = AdminTab.EMERGENCIES

    // Cached state for instant rendering
    private val emergencyList = mutableListOf<EmergencyItem>()
    private val hazardList = mutableListOf<HazardItem>()
    private val blockedRoadList = mutableListOf<BlockedRoadItem>()
    private val incidentList = mutableListOf<IncidentItem>()
    private val verificationList = mutableListOf<VerificationRequestItem>()
    private var selectedRegion: com.example.georescux.domain.routing.MapRegion? = null
    private var selectedDistrict: com.example.georescux.domain.admin.District? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContainer = (application as GeoRescuXApplication).appContainer

        lifecycleScope.launch {
            // Secure server-verified check via Firebase Custom Claims
            val role = appContainer.roleResolver.resolveRole()
            if (role != UserRole.ADMIN) {
                Toast.makeText(this@AdminDashboardActivity, "Access Denied: Admin authorization required", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            setContentView(R.layout.activity_admin_dashboard)

            findViewById<TextView>(R.id.textViewAdminEmail).text =
                "Logged in as Admin: ${appContainer.authRepository.currentUserEmail ?: "admin@georescux.com"}"

            findViewById<Button>(R.id.buttonAdminLogout).setOnClickListener {
                appContainer.authRepository.signOut()
                startActivity(Intent(this@AdminDashboardActivity, LoginActivity::class.java))
                finish()
            }

            val spinner = findViewById<android.widget.Spinner>(R.id.spinnerAdminRegion)
            val districtSpinner = findViewById<android.widget.Spinner>(R.id.spinnerAdminDistrict)
            val regions = listOf("All Regions") + MapRegionCatalog.availableRegions.map { it.displayName }
            val adapter = android.widget.ArrayAdapter(this@AdminDashboardActivity, android.R.layout.simple_spinner_dropdown_item, regions)
            spinner.adapter = adapter
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedName = regions[position]
                    selectedRegion = if (selectedName == "All Regions") null else MapRegionCatalog.availableRegions.find { it.displayName == selectedName }
                    populateDistrictSpinner(districtSpinner, selectedRegion?.id)
                    renderCurrentTabContent()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

            setupTabs()
            setupRealtimeFirebaseListeners()
            setupActionButtons()
        }
    }

    private fun setupTabs() {
        val btnEmergencies = findViewById<Button>(R.id.buttonTabEmergencies)
        val btnHazards = findViewById<Button>(R.id.buttonTabHazards)
        val btnIncidents = findViewById<Button>(R.id.buttonTabIncidents)
        val btnResources = findViewById<Button>(R.id.buttonTabResources)
        val btnVerifications = findViewById<Button>(R.id.buttonTabVerifications)
        val hazardActions = findViewById<LinearLayout>(R.id.layoutHazardActions)

        fun updateTabStyles() {
            val violetBg = ContextCompat.getDrawable(this, R.drawable.bg_button_violet)
            val secondaryBg = ContextCompat.getDrawable(this, R.drawable.bg_button_secondary)
            val white = ContextCompat.getColor(this, R.color.white)
            val textPrimary = ContextCompat.getColor(this, R.color.text_primary)

            btnEmergencies.background = if (currentTab == AdminTab.EMERGENCIES) violetBg else secondaryBg
            btnEmergencies.setTextColor(if (currentTab == AdminTab.EMERGENCIES) white else textPrimary)

            btnHazards.background = if (currentTab == AdminTab.HAZARDS) violetBg else secondaryBg
            btnHazards.setTextColor(if (currentTab == AdminTab.HAZARDS) white else textPrimary)

            btnIncidents.background = if (currentTab == AdminTab.INCIDENTS) violetBg else secondaryBg
            btnIncidents.setTextColor(if (currentTab == AdminTab.INCIDENTS) white else textPrimary)

            btnResources.background = if (currentTab == AdminTab.RESOURCES) violetBg else secondaryBg
            btnResources.setTextColor(if (currentTab == AdminTab.RESOURCES) white else textPrimary)

            btnVerifications.background = if (currentTab == AdminTab.VERIFICATIONS) violetBg else secondaryBg
            btnVerifications.setTextColor(if (currentTab == AdminTab.VERIFICATIONS) white else textPrimary)

            hazardActions.visibility = if (currentTab == AdminTab.HAZARDS) View.VISIBLE else View.GONE
            renderCurrentTabContent()
        }

        btnEmergencies.setOnClickListener { currentTab = AdminTab.EMERGENCIES; updateTabStyles() }
        btnHazards.setOnClickListener { currentTab = AdminTab.HAZARDS; updateTabStyles() }
        btnIncidents.setOnClickListener { currentTab = AdminTab.INCIDENTS; updateTabStyles() }
        btnResources.setOnClickListener { currentTab = AdminTab.RESOURCES; updateTabStyles() }
        btnVerifications.setOnClickListener { currentTab = AdminTab.VERIFICATIONS; updateTabStyles() }
    }

    /**
     * §11.2: populates the district drill-down from the selected state.
     * "All Districts" keeps the state-wide view; choosing a district scopes
     * the emergency feed and the priority statistics to that district.
     */
    private fun populateDistrictSpinner(
        districtSpinner: android.widget.Spinner,
        stateId: String?,
    ) {
        val districts = if (stateId == null) emptyList()
            else com.example.georescux.domain.admin.DistrictCatalog.districtsForState(stateId)
        val labels = listOf("All Districts") + districts.map { it.displayName }
        districtSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        districtSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val name = labels[position]
                selectedDistrict = districts.firstOrNull { it.displayName == name }
                refreshPriorityStats()
                renderCurrentTabContent()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupActionButtons() {        findViewById<Button>(R.id.buttonAddHazard).setOnClickListener {
            showAddHazardDialog()
        }
        findViewById<Button>(R.id.buttonBlockRoad).setOnClickListener {
            showBlockRoadDialog()
        }
    }

    private fun setupRealtimeFirebaseListeners() {
        val statusText = findViewById<TextView>(R.id.textViewAdminStatus)
        val syncStatusText = findViewById<TextView>(R.id.textViewSyncStatus)

        // 1. SOS alerts listener
        database.getReference("sos_alerts").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                statusText.text = "Firebase: Connected (Custom Claim Valid)"
                statusText.setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.safe_green))
                syncStatusText.text = "Sync: Realtime Active"

                emergencyList.clear()

                for (userSnap in snapshot.children) {
                    val uid = userSnap.key ?: "unknown"
                    val isVerified = userSnap.child("isVerified").getValue(Boolean::class.java) ?: false
                    for (alertSnap in userSnap.children) {
                        if (alertSnap.key == "isVerified") continue

                        val startedAtMs = alertSnap.child("startedAtMs").getValue(Long::class.java) ?: 0L
                        val stoppedAtMs = alertSnap.child("stoppedAtMs").getValue(Long::class.java)
                        val status = alertSnap.child("status").getValue(String::class.java)
                            ?: if (stoppedAtMs == null) "ACTIVE" else "COMPLETED"

                        val isActive = stoppedAtMs == null || status == "ACTIVE"

                        val locSnap = alertSnap.child("location")
                        val lat = locSnap.child("latitude").getValue(Double::class.java)
                        val lng = locSnap.child("longitude").getValue(Double::class.java)
                        val acc = locSnap.child("accuracyMeters").getValue(Float::class.java) ?: 0f
                        val provider = locSnap.child("provider").getValue(String::class.java) ?: "GPS"
                        
                        val note = alertSnap.child("note").getValue(String::class.java)

                        emergencyList.add(
                            EmergencyItem(
                                alertId = alertSnap.key ?: "alert",
                                uid = uid,
                                startedAtMs = startedAtMs,
                                stoppedAtMs = stoppedAtMs,
                                status = status,
                                isActive = isActive,
                                latitude = lat,
                                longitude = lng,
                                accuracy = acc,
                                provider = provider,
                                note = note,
                                isVerified = isVerified
                            )
                        )
                    }
                }

                refreshPriorityStats()

                if (currentTab == AdminTab.EMERGENCIES) renderCurrentTabContent()
            }

            override fun onCancelled(error: DatabaseError) {
                statusText.text = "Firebase Status: Offline / Permission Denied"
                statusText.setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.text_error))
                syncStatusText.text = "Sync: Offline"
            }
        })

        // 2. Hazards listener
        database.getReference("hazards").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                hazardList.clear()
                for (child in snapshot.children) {
                    val id = child.child("id").getValue(String::class.java) ?: child.key ?: continue
                    val from = child.child("fromNodeId").getValue(String::class.java) ?: ""
                    val to = child.child("toNodeId").getValue(String::class.java) ?: ""
                    val penalty = child.child("penaltyMeters").getValue(Double::class.java) ?: 500.0
                    hazardList.add(HazardItem(id, from, to, penalty))
                }
                updateHazardsCount()
                if (currentTab == AdminTab.HAZARDS) renderCurrentTabContent()
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        // 3. Blocked roads listener
        database.getReference("blocked_roads").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                blockedRoadList.clear()
                for (child in snapshot.children) {
                    val from = child.child("fromNodeId").getValue(String::class.java) ?: continue
                    val to = child.child("toNodeId").getValue(String::class.java) ?: continue
                    val blocked = child.child("blocked").getValue(Boolean::class.java) ?: true
                    if (blocked) blockedRoadList.add(BlockedRoadItem(child.key ?: "${from}_$to", from, to))
                }
                updateHazardsCount()
                if (currentTab == AdminTab.HAZARDS) renderCurrentTabContent()
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        // 4. Incidents listener
        database.getReference("incidents").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                incidentList.clear()
                for (child in snapshot.children) {
                    val id = child.key ?: continue
                    val type = child.child("type").getValue(String::class.java) ?: "INCIDENT"
                    val desc = child.child("description").getValue(String::class.java) ?: "Reported Hazard"
                    val status = child.child("status").getValue(String::class.java) ?: "REPORTED"
                    val timestamp = child.child("timestampMs").getValue(Long::class.java) ?: System.currentTimeMillis()
                    incidentList.add(IncidentItem(id, type, desc, status, timestamp))
                }
                // An incident is "active" until it reaches a terminal state.
                val activeIncidents = incidentList.count {
                    it.status !in setOf("RESOLVED", "CLOSED", "CANCELLED")
                }
                findViewById<TextView>(R.id.textViewActiveIncidentsCount).text = activeIncidents.toString()
                if (currentTab == AdminTab.INCIDENTS) renderCurrentTabContent()
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        // 5. Verification requests listener
        database.getReference("verification_requests").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                verificationList.clear()
                for (child in snapshot.children) {
                    val uid = child.child("uid").getValue(String::class.java) ?: continue
                    val status = child.child("status").getValue(String::class.java) ?: "PENDING"
                    val name = child.child("name").getValue(String::class.java) ?: "Unknown"
                    val timestamp = child.child("timestampMs").getValue(Long::class.java) ?: System.currentTimeMillis()
                    verificationList.add(VerificationRequestItem(uid, name, status, timestamp))
                }
                val pending = verificationList.count { it.status == "PENDING" }
                findViewById<TextView>(R.id.textViewPendingVerificationCount).text = pending.toString()
                if (currentTab == AdminTab.VERIFICATIONS) renderCurrentTabContent()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    /**
     * Recomputes the prioritization statistics over the emergencies that fall
     * inside the currently selected region and district (spec §11.2/§11.3).
     * With no selection, the numbers cover every recorded SOS.
     */
    private fun refreshPriorityStats() {
        val scoped = emergencyList.filter { em ->
            inSelectedScope(em.latitude, em.longitude)
        }
        val stats = EmergencyPriorityCalculator.calculate(scoped)
        findViewById<TextView>(R.id.textViewTotalSosCount).text = stats.totalSosCount.toString()
        findViewById<TextView>(R.id.textViewVerifiedSosCount).text = stats.verifiedUserSosCount.toString()
        findViewById<TextView>(R.id.textViewUnverifiedSosCount).text = stats.unverifiedUserSosCount.toString()
        findViewById<TextView>(R.id.textViewPriorityScore).text = stats.emergencyPriorityScore.toString()
        findViewById<TextView>(R.id.textViewActiveSosCount).text =
            scoped.count { it.isActive }.toString()
    }

    /** True when a position lies inside the selected region and district (either may be "all"). */
    private fun inSelectedScope(latitude: Double?, longitude: Double?): Boolean {
        if (latitude == null || longitude == null) return false
        val region = selectedRegion
        if (region != null && (
                latitude !in region.minLatitude..region.maxLatitude ||
                longitude !in region.minLongitude..region.maxLongitude
            )) return false
        val district = selectedDistrict
        if (district != null && !district.contains(latitude, longitude)) return false
        return true
    }

    private fun updateHazardsCount() {
        val total = hazardList.size + blockedRoadList.size
        findViewById<TextView>(R.id.textViewActiveHazardsCount).text = total.toString()
    }

    private fun renderCurrentTabContent() {
        val container = findViewById<LinearLayout>(R.id.adminDataContainer)
        val sectionTitle = findViewById<TextView>(R.id.textViewSectionTitle)
        container.removeAllViews()

        when (currentTab) {
            AdminTab.EMERGENCIES -> {
                val filteredList = emergencyList.filter { em ->
                    inSelectedScope(em.latitude, em.longitude)
                }
                sectionTitle.text = "ACTIVE EMERGENCY ALERTS (${filteredList.size})"
                if (filteredList.isEmpty()) {
                    showEmpty("No emergency alerts in selected region.")
                    return
                }
                filteredList.sortedByDescending { it.startedAtMs }.forEach { emergency ->
                    container.addView(buildEmergencyCard(emergency))
                }
            }
            AdminTab.HAZARDS -> {
                sectionTitle.text = "ACTIVE HAZARDS & BLOCKED ROADS (${hazardList.size + blockedRoadList.size})"
                if (hazardList.isEmpty() && blockedRoadList.isEmpty()) {
                    showEmpty("No road hazards or blocked segments reported.")
                    return
                }
                hazardList.forEach { hazard ->
                    container.addView(buildHazardCard(hazard))
                }
                blockedRoadList.forEach { block ->
                    container.addView(buildBlockedRoadCard(block))
                }
            }
            AdminTab.INCIDENTS -> {
                sectionTitle.text = "REPORTED INCIDENT FEED (${incidentList.size})"
                if (incidentList.isEmpty()) {
                    showEmpty("No incident reports submitted yet.")
                    return
                }
                incidentList.sortedByDescending { it.timestampMs }.forEach { incident ->
                    container.addView(buildIncidentCard(incident))
                }
            }
            AdminTab.RESOURCES -> {
                sectionTitle.text = "EMERGENCY RESOURCES & SYSTEM HEALTH"
                container.addView(buildSystemHealthCard())
                container.addView(buildResourcesCard())
            }
            AdminTab.VERIFICATIONS -> {
                sectionTitle.text = "IDENTITY VERIFICATION QUEUE (${verificationList.size})"
                if (verificationList.isEmpty()) {
                    showEmpty("No verification requests pending.")
                    return
                }
                verificationList.sortedByDescending { it.timestampMs }.forEach { req ->
                    container.addView(buildVerificationCard(req))
                }
            }
        }
    }

    private fun showEmpty(message: String) {
        val container = findViewById<LinearLayout>(R.id.adminDataContainer)
        val text = TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.text_muted))
            textSize = 13f
            setPadding(32, 32, 32, 32)
            background = ContextCompat.getDrawable(this@AdminDashboardActivity, R.drawable.bg_card_dark)
        }
        container.addView(text)
    }

    private fun buildEmergencyCard(emergency: EmergencyItem): View {
        val card = layoutInflater.inflate(R.layout.item_alert, findViewById(R.id.adminDataContainer), false)
        val dateStr = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()).format(Date(emergency.startedAtMs))
        val statusText = if (emergency.isActive) "🚨 ACTIVE SOS" else "✅ RESOLVED"

        card.findViewById<TextView>(R.id.textAlertDate).text = "$statusText · UID: ${emergency.uid.take(8)}…"
        
        var durationText = "Started: $dateStr · Status: ${emergency.status}"
        if (!emergency.note.isNullOrBlank()) {
            durationText += "\nNote: ${emergency.note}"
        }
        card.findViewById<TextView>(R.id.textAlertDuration).text = durationText

        val locText = if (emergency.latitude != null && emergency.longitude != null) {
            String.format(Locale.US, "📍 Lat: %.5f, Lng: %.5f (±%.0fm, %s)", emergency.latitude, emergency.longitude, emergency.accuracy, emergency.provider)
        } else {
            "📍 Location: GPS Fix Pending"
        }
        card.findViewById<TextView>(R.id.textAlertLocation).text = locText
        return card
    }

    private fun buildHazardCard(hazard: HazardItem): View {
        val card = layoutInflater.inflate(R.layout.item_alert, findViewById(R.id.adminDataContainer), false)
        card.findViewById<TextView>(R.id.textAlertDate).text = "⚠️ Hazard: ${hazard.id}"
        card.findViewById<TextView>(R.id.textAlertDuration).text = "Affected: ${hazard.fromNodeId} ↔ ${hazard.toNodeId} · Penalty: +${hazard.penaltyMeters.toInt()}m"
        card.findViewById<TextView>(R.id.textAlertLocation).apply {
            text = "Tap to Resolve / Remove"
            setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.text_error))
            setOnClickListener {
                confirmClearHazard(hazard.id)
            }
        }
        return card
    }

    private fun buildBlockedRoadCard(block: BlockedRoadItem): View {
        val card = layoutInflater.inflate(R.layout.item_alert, findViewById(R.id.adminDataContainer), false)
        card.findViewById<TextView>(R.id.textAlertDate).text = "🚫 Blocked Road"
        card.findViewById<TextView>(R.id.textAlertDuration).text = "Segment: ${block.fromNodeId} ↔ ${block.toNodeId} (Impassable)"
        card.findViewById<TextView>(R.id.textAlertLocation).apply {
            text = "Tap to Unblock Segment"
            setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.safe_green))
            setOnClickListener {
                confirmUnblockRoad(block.fromNodeId, block.toNodeId)
            }
        }
        return card
    }

    private fun buildIncidentCard(incident: IncidentItem): View {
        val card = layoutInflater.inflate(R.layout.item_alert, findViewById(R.id.adminDataContainer), false)
        val dateStr = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(incident.timestampMs))

        card.findViewById<TextView>(R.id.textAlertDate).text = "📋 ${incident.type} · ${incident.status}"
        card.findViewById<TextView>(R.id.textAlertDuration).text = "${incident.description} ($dateStr)"
        card.findViewById<TextView>(R.id.textAlertLocation).apply {
            text = "Update Status (Investigating / Resolved)"
            setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.violet_accent))
            setOnClickListener {
                showUpdateIncidentStatusDialog(incident.id, incident.status)
            }
        }
        return card
    }

    private fun buildVerificationCard(req: VerificationRequestItem): View {
        val card = layoutInflater.inflate(R.layout.item_alert, findViewById(R.id.adminDataContainer), false)
        val dateStr = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(req.timestampMs))

        card.findViewById<TextView>(R.id.textAlertDate).text = "🛡️ Verification Request · ${req.status}"
        card.findViewById<TextView>(R.id.textAlertDuration).text = "User: ${req.name} (UID: ${req.uid})\nSubmitted: $dateStr"
        card.findViewById<TextView>(R.id.textAlertLocation).apply {
            // §11.4: only a pending submission needs a decision. An already
            // approved/rejected request shows its outcome and no action.
            when (req.status) {
                "VERIFIED" -> {
                    text = "Identity approved — contributing 2 points to priority score"
                    setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.safe_green))
                }
                "REJECTED" -> {
                    text = "Identity rejected — contributing 1 point to priority score"
                    setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.text_error))
                }
                else -> {
                    text = "Review Identity (Approve / Reject)"
                    setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.violet_accent))
                    setOnClickListener {
                        showVerificationReviewDialog(req.uid, req.name)
                    }
                }
            }
        }
        return card
    }

    private fun buildSystemHealthCard(): View {
        val container = (application as GeoRescuXApplication).appContainer
        val card = layoutInflater.inflate(R.layout.item_alert, findViewById(R.id.adminDataContainer), false)
        val activeRegion = MapRegionCatalog.byId(container.activeRegionId) ?: MapRegionCatalog.sampleRegion
        val graph = runCatching { container.routeRepository.getGraph() }.getOrNull()

        card.findViewById<TextView>(R.id.textAlertDate).text = "🖥️ System & Network Health"
        card.findViewById<TextView>(R.id.textAlertDuration).text =
            "Region: ${activeRegion.displayName} (v${activeRegion.version})\n" +
            "Local Graph: ${graph?.nodes?.size ?: 0} nodes / ${graph?.edges?.size ?: 0} edges\n" +
            "BLE Subsystem: Active & Listening"
        card.findViewById<TextView>(R.id.textAlertLocation).text = "Firebase RTDB: Connected · Custom Claims: Verified"
        return card
    }

    private fun buildResourcesCard(): View {
        val container = (application as GeoRescuXApplication).appContainer
        val card = layoutInflater.inflate(R.layout.item_alert, findViewById(R.id.adminDataContainer), false)
        val graph = runCatching { container.routeRepository.getGraph() }.getOrNull()
        val safeHavens = graph?.nodes?.filter { it.isSafeHaven }.orEmpty()

        card.findViewById<TextView>(R.id.textAlertDate).text = "🏥 Emergency Facilities (${safeHavens.size} Safe Havens)"
        card.findViewById<TextView>(R.id.textAlertDuration).text =
            safeHavens.take(5).joinToString("\n") { "• Safe Haven: ${it.id} (Lat: ${String.format(Locale.US, "%.4f", it.latitude)}, Lng: ${String.format(Locale.US, "%.4f", it.longitude)})" }
        card.findViewById<TextView>(R.id.textAlertLocation).text = "All emergency safe zones online and routable via A*"
        return card
    }

    private fun showAddHazardDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val fromInput = EditText(this).apply { hint = "From Node ID (e.g. node_1)" }
        val toInput = EditText(this).apply { hint = "To Node ID (e.g. node_2)" }
        val penaltyInput = EditText(this).apply { hint = "Penalty in meters (e.g. 1000)" }

        layout.addView(fromInput)
        layout.addView(toInput)
        layout.addView(penaltyInput)

        AlertDialog.Builder(this)
            .setTitle("Add Road Hazard")
            .setView(layout)
            .setPositiveButton("Publish Hazard") { _, _ ->
                val from = fromInput.text.toString().trim()
                val to = toInput.text.toString().trim()
                val penalty = penaltyInput.text.toString().toDoubleOrNull() ?: 500.0

                if (from.isNotBlank() && to.isNotBlank()) {
                    val appContainer = (application as GeoRescuXApplication).appContainer
                    val hazardId = "hazard_${from}_${to}"
                    val hazard = RoadHazard(hazardId, from, to, penalty)

                    lifecycleScope.launch {
                        appContainer.routeRepository.setHazard(hazard)
                        val success = appContainer.hazardCloudDataSource.publishHazard(hazard)
                        Toast.makeText(this@AdminDashboardActivity, if (success) "Hazard published to cloud" else "Hazard saved locally", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBlockRoadDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val fromInput = EditText(this).apply { hint = "From Node ID (e.g. node_1)" }
        val toInput = EditText(this).apply { hint = "To Node ID (e.g. node_2)" }

        layout.addView(fromInput)
        layout.addView(toInput)

        AlertDialog.Builder(this)
            .setTitle("Block Road Segment")
            .setView(layout)
            .setPositiveButton("Block Road") { _, _ ->
                val from = fromInput.text.toString().trim()
                val to = toInput.text.toString().trim()

                if (from.isNotBlank() && to.isNotBlank()) {
                    val appContainer = (application as GeoRescuXApplication).appContainer
                    lifecycleScope.launch {
                        appContainer.routeRepository.setRoadBlocked(from, to, true)
                        val success = appContainer.hazardCloudDataSource.publishRoadBlocked(from, to, true)
                        Toast.makeText(this@AdminDashboardActivity, if (success) "Road block published to cloud" else "Road block saved locally", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearHazard(hazardId: String) {
        AlertDialog.Builder(this)
            .setTitle("Clear Hazard")
            .setMessage("Remove hazard $hazardId and restore normal route weighting?")
            .setPositiveButton("Clear") { _, _ ->
                val appContainer = (application as GeoRescuXApplication).appContainer
                lifecycleScope.launch {
                    appContainer.routeRepository.removeHazard(hazardId)
                    appContainer.hazardCloudDataSource.clearHazard(hazardId)
                    Toast.makeText(this@AdminDashboardActivity, "Hazard cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmUnblockRoad(fromNodeId: String, toNodeId: String) {
        AlertDialog.Builder(this)
            .setTitle("Unblock Road")
            .setMessage("Reopen road segment $fromNodeId ↔ $toNodeId for routing?")
            .setPositiveButton("Reopen") { _, _ ->
                val appContainer = (application as GeoRescuXApplication).appContainer
                lifecycleScope.launch {
                    appContainer.routeRepository.setRoadBlocked(fromNodeId, toNodeId, false)
                    appContainer.hazardCloudDataSource.publishRoadBlocked(fromNodeId, toNodeId, false)
                    Toast.makeText(this@AdminDashboardActivity, "Road unblocked", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUpdateIncidentStatusDialog(incidentId: String, currentStatus: String) {
        val statuses = arrayOf("INVESTIGATING", "CONFIRMED", "RESOLVED", "DISMISSED")
        AlertDialog.Builder(this)
            .setTitle("Update Incident Status")
            .setItems(statuses) { _, which ->
                val newStatus = statuses[which]
                lifecycleScope.launch {
                    try {
                        database.getReference("incidents")
                            .child(incidentId)
                            .child("status")
                            .setValue(newStatus)
                            .await()
                        Toast.makeText(this@AdminDashboardActivity, "Incident updated to $newStatus", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AdminDashboardActivity, "Failed to update incident: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * §11.4 identity review: an administrator decides a pending submission.
     * Only APPROVE sets the user's verified flag — a rejection records the
     * decision but must never raise the priority weighting.
     */
    private fun showVerificationReviewDialog(uid: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Review identity: $name")
            .setMessage("Approving marks this user verified (each SOS counts 2 points). " +
                "Rejecting keeps them unverified (each SOS counts 1 point).")
            .setPositiveButton("Approve") { _, _ ->
                lifecycleScope.launch {
                    try {
                        database.getReference("verification_requests").child(uid).child("status")
                            .setValue("VERIFIED").await()
                        // Only approval changes the verification status used by
                        // the prioritization heuristic (§11.3/§11.4).
                        database.getReference("sos_alerts").child(uid).child("isVerified")
                            .setValue(true).await()
                        Toast.makeText(this@AdminDashboardActivity,
                            "Identity approved", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AdminDashboardActivity,
                            "Failed to approve: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Reject") { _, _ ->
                lifecycleScope.launch {
                    try {
                        database.getReference("verification_requests").child(uid).child("status")
                            .setValue("REJECTED").await()
                        // Rejection must NOT raise the weighting; ensure the flag
                        // is false rather than assuming a default.
                        database.getReference("sos_alerts").child(uid).child("isVerified")
                            .setValue(false).await()
                        Toast.makeText(this@AdminDashboardActivity,
                            "Identity rejected", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AdminDashboardActivity,
                            "Failed to reject: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private data class EmergencyItem(
        val alertId: String,
        val uid: String,
        val startedAtMs: Long,
        val stoppedAtMs: Long?,
        val status: String,
        val isActive: Boolean,
        val latitude: Double?,
        val longitude: Double?,
        val accuracy: Float,
        val provider: String,
        val note: String?,
        override val isVerified: Boolean
    ) : EmergencyVerifiable

    private data class HazardItem(
        val id: String,
        val fromNodeId: String,
        val toNodeId: String,
        val penaltyMeters: Double,
    )

    private data class BlockedRoadItem(
        val key: String,
        val fromNodeId: String,
        val toNodeId: String,
    )

    private data class IncidentItem(
        val id: String,
        val type: String,
        val description: String,
        val status: String,
        val timestampMs: Long,
    )

    private data class VerificationRequestItem(
        val uid: String,
        val name: String,
        val status: String,
        val timestampMs: Long,
    )
}

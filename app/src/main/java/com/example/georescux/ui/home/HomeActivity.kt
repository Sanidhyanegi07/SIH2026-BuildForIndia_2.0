package com.example.georescux.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.ble.GeoRescueBleForegroundService
import com.example.georescux.domain.sos.SosEvent
import com.example.georescux.domain.sos.SosStateMachine
import com.example.georescux.domain.sos.SosState
import com.example.georescux.ui.alerts.AlertsActivity
import com.example.georescux.ui.auth.LoginActivity
import com.example.georescux.ui.common.ActiveSosBanner
import com.example.georescux.ui.contacts.ContactsActivity
import com.example.georescux.ui.routing.RouteActivity
import com.example.georescux.ui.sos.SosActivity
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocationStatus
import androidx.lifecycle.lifecycleScope
import com.example.georescux.domain.ble.GeoRescueBleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard screen. Shows the signed-in user, status placeholders and the
 * SOS press-and-hold button. Authentication goes through the repository —
 * no FirebaseAuth here.
 *
 * The SOS arming gesture is driven by [SosStateMachine]: holding for
 * 2 seconds hands the countdown over to [SosActivity]; releasing early
 * cancels silently and creates no SOS record.
 */
class HomeActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter
    }

    private val blePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // FIX Bug 6: use per-permission checks for the critical BLE subset instead of
        // all { it }, which would block mesh startup if NEARBY_WIFI_DEVICES (optional on
        // Android 12) or any other non-critical permission was denied.
        val criticalGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                permissions[Manifest.permission.BLUETOOTH_ADVERTISE] == true &&
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        }
        if (criticalGranted) {
            checkAndRequestBluetooth()
            startBleMeshIfPermissionsGranted()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> }

    // The dashboard's own view of the SOS state machine (arming only).
    // The countdown and emergency states live in SosActivity/SosViewModel.
    private var sosMachineState = SosState.IDLE

    private lateinit var recentActivitySummaryText: TextView
    private lateinit var textOfflineRelayBadge: TextView
    private lateinit var textRelayBtStatus: TextView
    private lateinit var textRelayScanStatus: TextView
    private lateinit var textRelayAdvStatus: TextView
    private lateinit var textRelayNodesCount: TextView
    private lateinit var textRelayReceivedCount: TextView
    private lateinit var textRelayForwardedCount: TextView
    private lateinit var textRelayPendingSync: TextView
    private var relayStatusJob: Job? = null
    private lateinit var connectivityBadge: com.example.georescux.ui.common.ConnectivityBadge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val container = (application as GeoRescuXApplication).appContainer
        val authRepository = container.authRepository

        findViewById<TextView>(R.id.textViewUserEmail).text =
            "Logged in as: ${authRepository.currentUserEmail ?: "unknown user"}"

        // Live connection indicator (spec §8): ONLINE only when cloud sync is
        // reachable; OFFLINE makes clear the app itself keeps working.
        connectivityBadge = com.example.georescux.ui.common.ConnectivityBadge(this)
        connectivityBadge.bind(findViewById(R.id.textViewConnectionStatus))

        com.example.georescux.ui.common.HelpLauncher.bind(this)

        findViewById<Button>(R.id.buttonLogout).setOnClickListener {
            authRepository.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            // Close Home so that pressing Back does not return to a logged-in screen.
            finish()
        }

        // Offline relay status card bindings (Section 16)
        textOfflineRelayBadge = findViewById(R.id.textOfflineRelayBadge)
        textRelayBtStatus = findViewById(R.id.textRelayBtStatus)
        textRelayScanStatus = findViewById(R.id.textRelayScanStatus)
        textRelayAdvStatus = findViewById(R.id.textRelayAdvStatus)
        textRelayNodesCount = findViewById(R.id.textRelayNodesCount)
        textRelayReceivedCount = findViewById(R.id.textRelayReceivedCount)
        textRelayForwardedCount = findViewById(R.id.textRelayForwardedCount)
        textRelayPendingSync = findViewById(R.id.textRelayPendingSync)

        setupSosButton()

        // Emergency Contacts tile (Stage 3).
        findViewById<View>(R.id.tileEmergencyContacts).setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        // Alerts tile + Recent activity summary (Stage 4, read-only).
        recentActivitySummaryText = findViewById(R.id.textViewRecentActivitySummary)
        findViewById<View>(R.id.tileAlerts).setOnClickListener {
            startActivity(Intent(this, AlertsActivity::class.java))
        }

        // Safe Route tile (Stage 7A): offline evacuation routing.
        findViewById<View>(R.id.tileSafeRoute).setOnClickListener {
            startActivity(Intent(this, RouteActivity::class.java))
        }

        // BLE Diagnostics tile: two-device emergency mesh testing surface.
        findViewById<View>(R.id.tileBleDiagnostics).setOnClickListener {
            startActivity(Intent(this, com.example.georescux.ui.ble.BleDiagnosticsActivity::class.java))
        }

        // Profile tile: photo, phone number and email (device-local).
        findViewById<View>(R.id.tileProfile).setOnClickListener {
            startActivity(Intent(this, com.example.georescux.ui.profile.ProfileActivity::class.java))
        }

        // Primary bottom navigation: HOME · MAP · ALERTS · PROFILE (spec §9).
        com.example.georescux.ui.common.BottomNav.bind(this, R.id.nav_home)
    }

    override fun onDestroy() {
        connectivityBadge.unbind()
        super.onDestroy()
    }

    private fun setupSosButton() {
        val sosButton = findViewById<Button>(R.id.buttonSos)

        // §3: a single tap starts the 2-second countdown. The old
        // press-and-hold arming stage is removed so the total time from
        // tap to ACTIVE is exactly the countdown.
        sosButton.setOnClickListener {
            // §24: if an emergency is already active, don't create a second
            // one — open the live emergency panel (which is non-trapping).
            if ((application as GeoRescuXApplication).appContainer.sosRepository.getActiveEmergency() != null) {
                openSosScreen()
                return@setOnClickListener
            }
            sosMachineState = SosStateMachine.onEvent(sosMachineState, SosEvent.HOLD_STARTED)
            openSosScreen()
        }
    }

    private fun openSosScreen() {
        // The countdown continues in SosActivity; the dashboard's own machine
        // view hands the countdown off and returns to IDLE.
        if (sosMachineState == SosState.COUNTDOWN) {
            sosMachineState = SosStateMachine.onEvent(sosMachineState, SosEvent.COUNTDOWN_CANCELLED)
        }
        startActivity(Intent(this, SosActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        checkBlePermissionsAndState()

        // §4/§21: an active SOS is a GLOBAL state, not a screen lock. Never
        // re-launch SosActivity here — the persistent banner on this screen
        // is the user's way back, and they are free to use the rest of the app.
        ActiveSosBanner.refresh(this)

        // Refresh the latest-alert summary so a newly completed SOS
        // appears here without any unrelated changes.
        refreshRecentActivitySummary()
        startRelayStatusUpdates()
    }

    override fun onPause() {
        super.onPause()
        relayStatusJob?.cancel()
        relayStatusJob = null
    }

    private fun startRelayStatusUpdates() {
        relayStatusJob?.cancel()
        relayStatusJob = lifecycleScope.launch {
            while (isActive) {
                updateRelayStatusUi()
                delay(2000L)
            }
        }
    }

    private fun updateRelayStatusUi() {
        val app = application as? GeoRescuXApplication ?: return
        val bleManager = app.appContainer.bleManager
        val authRepo = app.appContainer.authRepository
        val syncStore = app.appContainer.syncStateStore

        val btEnabled = bluetoothAdapter?.isEnabled == true
        textRelayBtStatus.text = "● Bluetooth: " + if (btEnabled) "ON" else "OFF"
        textRelayBtStatus.setTextColor(ContextCompat.getColor(this, if (btEnabled) R.color.text_primary else R.color.sos_red))

        val diag = bleManager.diagnostics()
        val isScanning = diag.scanning
        val isAdv = diag.advertising

        textRelayScanStatus.text = "● Scanning: " + if (isScanning) "ACTIVE" else "IDLE"
        textRelayAdvStatus.text = "● Broadcasting: " + if (isAdv) "ACTIVE" else "IDLE"

        val activeNodes = diag.peers.count { it.state == GeoRescueBleState.READY || it.state == GeoRescueBleState.CONNECTED }
        val totalNodes = if (activeNodes > 0) activeNodes else diag.nearbyCount
        textRelayNodesCount.text = "● Nearby relay nodes: $totalNodes"

        val received = diag.seenPackets
        textRelayReceivedCount.text = "● Messages received: $received"
        val forwarded = (received - diag.pendingOutbound).coerceAtLeast(0)
        textRelayForwardedCount.text = "● Messages forwarded: $forwarded"

        val uid = authRepo.currentUserId
        val pendingCount = if (uid != null) syncStore.pendingSosAlertIds(uid).size else diag.pendingOutbound
        textRelayPendingSync.text = "● Pending sync: $pendingCount"

        val isRelayActive = btEnabled && (isScanning || isAdv || activeNodes > 0)
        textOfflineRelayBadge.text = if (isRelayActive) "● ACTIVE" else "● PAUSED"
        textOfflineRelayBadge.setTextColor(ContextCompat.getColor(this, if (isRelayActive) R.color.safe_green else R.color.text_muted))
    }

    private fun checkBlePermissionsAndState() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val list = mutableListOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                // Request notification permission here so BLE SOS alerts can be shown
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            list.toTypedArray()
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            blePermissionsLauncher.launch(missing.toTypedArray())
        } else {
            checkAndRequestBluetooth()
            checkAndRequestLocationService()
            startBleMeshIfPermissionsGranted()
        }
    }

    private fun startBleMeshIfPermissionsGranted() {
        val deviceName = Build.MODEL ?: "GeoRescuXDevice"
        (application as GeoRescuXApplication).appContainer.relayConnectionManager.startMesh(deviceName)
        // Start foreground service to maintain BLE offline emergency mesh lifecycle
        // across app minimize, backgrounding, and screen locks.
        runCatching {
            GeoRescueBleForegroundService.start(this)
        }
    }

    private fun checkAndRequestBluetooth() {
        val adapter = bluetoothAdapter
        if (adapter != null && !adapter.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableIntent)
        }
    }

    private fun checkAndRequestLocationService() {
        val locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val isNetworkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        if (!isGpsEnabled && !isNetworkEnabled) {
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) { }
        }
    }

    private fun refreshRecentActivitySummary() {
        val latest = (application as GeoRescuXApplication)
            .appContainer.sosRepository.getHistory().firstOrNull()

        recentActivitySummaryText.text = if (latest == null) {
            "No alerts yet"
        } else {
            buildRecentActivitySummary(latest)
        }
    }

    private fun buildRecentActivitySummary(latest: SosEmergency): String {
        val date = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(latest.startedAtMs))
        val summary = StringBuilder("$date · ${formatDuration(latest)}")
        when (latest.locationStatus) {
            SosLocationStatus.ACQUIRED -> summary.append(" · 📍 Location found")
            SosLocationStatus.PERMISSION_MISSING -> summary.append(" · Location permission needed")
            SosLocationStatus.UNAVAILABLE -> summary.append(" · Location unavailable")
            null -> Unit
        }
        return summary.toString()
    }

    private fun formatDuration(alert: SosEmergency): String {
        val stoppedAt = alert.stoppedAtMs ?: return "ongoing"
        val totalSeconds = ((stoppedAt - alert.startedAtMs) / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private companion object
}
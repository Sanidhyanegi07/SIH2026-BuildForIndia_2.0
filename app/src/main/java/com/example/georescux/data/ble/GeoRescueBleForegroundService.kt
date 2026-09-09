package com.example.georescux.data.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.domain.ble.GeoRescueBleDiagnostics
import com.example.georescux.domain.ble.GeoRescueBlePermissions
import com.example.georescux.domain.ble.GeoRescueBleState
import com.example.georescux.ui.ble.BleDiagnosticsActivity
import com.example.georescux.ui.home.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the GeoRescueX BLE mesh network operational
 * when the app is backgrounded, minimized, or the device screen is locked.
 *
 * Responsibilities:
 * 1. Manages BLE advertising and GATT server readiness.
 * 2. Implements a 30s scan / 10s pause duty cycle to prevent Android BLE scan throttling.
 * 3. Monitors Bluetooth adapter toggling via [BluetoothStateReceiver] and recovers automatically.
 * 4. Periodically attempts reconnection to nearby discovered peers.
 * 5. Displays an ongoing status notification showing active peers and relay readiness.
 */
class GeoRescueBleForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanCycleJob: Job? = null
    private var statusUpdateJob: Job? = null
    private var bluetoothReceiver: BluetoothStateReceiver? = null

    private val bleManager: GeoRescueBleManager?
        get() = (application as? GeoRescuXApplication)?.appContainer?.bleManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        // Register Bluetooth broadcast receiver for automatic on/off recovery
        val receiver = BluetoothStateReceiver(
            onBluetoothEnabled = {
                handleBluetoothEnabled()
            },
            onBluetoothDisabled = {
                handleBluetoothDisabled()
            }
        )
        bluetoothReceiver = receiver
        try {
            registerReceiver(receiver, BluetoothStateReceiver.createIntentFilter())
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_ERROR,
                "Failed to register BluetoothStateReceiver: ${e.message}"
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            GeoRescueBleDiagnostics.info(GeoRescueBleDiagnostics.DISCONNECTED, "Stopping BLE Foreground Service")
            stopMeshOperations()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground with appropriate type
        val notification = buildNotification(statusText = "Initializing mesh relay...")
        startInForeground(notification)

        startMeshOperations()

        return START_STICKY
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMeshOperations() {
        val manager = bleManager ?: return

        // Verify critical permissions before attempting BLE operations
        val hasPermissions = GeoRescueBlePermissions.missingCritical(
            Build.VERSION.SDK_INT
        ) { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.isEmpty()

        if (!hasPermissions) {
            GeoRescueBleDiagnostics.warn(
                GeoRescueBleDiagnostics.BLE_INIT_FAILED,
                "Cannot start BLE service: critical permissions missing"
            )
            updateNotification("Permissions required to run mesh relay")
            return
        }

        // Initialize BLE manager and GATT server
        try {
            if (manager.initialize()) {
                manager.startAdvertising()
            }
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_ERROR,
                "Failed to initialize BLE manager: ${e.message}"
            )
        }

        startScanDutyCycle()
        startPeriodicStatusUpdates()
    }

    private fun stopMeshOperations() {
        scanCycleJob?.cancel()
        scanCycleJob = null
        statusUpdateJob?.cancel()
        statusUpdateJob = null

        val manager = bleManager ?: return
        try {
            manager.stopScanning()
            manager.stopAdvertising()
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_ERROR,
                "Error stopping BLE scanning/advertising: ${e.message}"
            )
        }
    }

    private fun handleBluetoothEnabled() {
        val manager = bleManager ?: return
        try {
            if (manager.initialize()) {
                manager.startAdvertising()
            }
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_ERROR,
                "Error reinitializing BLE on adapter enabled: ${e.message}"
            )
        }
        startScanDutyCycle()
        updateNotification("Bluetooth re-enabled. Mesh active.")
    }

    private fun handleBluetoothDisabled() {
        val manager = bleManager ?: return
        try {
            manager.stopScanning()
            manager.stopAdvertising()
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_ERROR,
                "Error pausing BLE on adapter disabled: ${e.message}"
            )
        }
        scanCycleJob?.cancel()
        scanCycleJob = null
        updateNotification("Bluetooth is off. Mesh relay paused.")
    }

    /**
     * Duty cycle: 30s scan -> 10s pause -> repeat.
     * Prevents Android scan throttling while maintaining discoverability.
     */
    private fun startScanDutyCycle() {
        scanCycleJob?.cancel()
        scanCycleJob = serviceScope.launch {
            val manager = bleManager ?: return@launch

            while (isActive) {
                // Scan Phase: 30 seconds
                if (manager.initialized) {
                    try {
                        manager.startScanning()
                    } catch (e: Exception) {
                        GeoRescueBleDiagnostics.error(
                            GeoRescueBleDiagnostics.BLE_ERROR,
                            "Error starting scan cycle: ${e.message}"
                        )
                    }
                }
                refreshNotificationFromState()

                // During the 30s scan window, periodically retry connections to discovered peers
                var elapsedMs = 0L
                val scanWindowMs = 30_000L
                val retryIntervalMs = 5_000L
                while (isActive && elapsedMs < scanWindowMs) {
                    delay(retryIntervalMs)
                    elapsedMs += retryIntervalMs
                    retryDiscoveredPeers(manager)
                }

                // Pause Phase: 10 seconds (advertising stays active so other devices can still discover us)
                if (manager.initialized) {
                    try {
                        manager.stopScanning()
                    } catch (e: Exception) {
                        GeoRescueBleDiagnostics.error(
                            GeoRescueBleDiagnostics.BLE_ERROR,
                            "Error pausing scan cycle: ${e.message}"
                        )
                    }
                }
                refreshNotificationFromState()
                delay(10_000L)
            }
        }
    }

    /**
     * Connects to recently discovered nodes that are not currently connected.
     */
    private fun retryDiscoveredPeers(manager: GeoRescueBleManager) {
        if (!manager.initialized) return
        try {
            val diag = manager.diagnostics()
            val connectedAddresses = diag.peers.filter {
                it.state == GeoRescueBleState.READY ||
                    it.state == GeoRescueBleState.CONNECTED ||
                    it.state == GeoRescueBleState.CONNECTING
            }.map { it.address }.toSet()

            val now = System.currentTimeMillis()
            val candidateDevices = diag.nearbyDevices.filter { device ->
                !connectedAddresses.contains(device.address) &&
                    (now - device.lastSeenMs < PEER_RECONNECT_WINDOW_MS)
            }

            for (device in candidateDevices) {
                manager.connectTo(device.address)
            }
        } catch (e: Exception) {
            GeoRescueBleDiagnostics.warn(
                GeoRescueBleDiagnostics.BLE_ERROR,
                "Error retrying discovered peers: ${e.message}"
            )
        }
    }

    private fun startPeriodicStatusUpdates() {
        statusUpdateJob?.cancel()
        statusUpdateJob = serviceScope.launch {
            while (isActive) {
                delay(15_000L)
                refreshNotificationFromState()
            }
        }
    }

    private fun refreshNotificationFromState() {
        val manager = bleManager
        val (statusText, detailText) = if (manager == null || !manager.initialized) {
            "Mesh offline • Radio initializing" to "Initializing Bluetooth Low Energy mesh stack..."
        } else {
            val diag = manager.diagnostics()
            val activePeers = diag.peers.count {
                it.state == GeoRescueBleState.READY || it.state == GeoRescueBleState.CONNECTED
            }
            val status = when {
                activePeers > 0 -> "Connected to $activePeers peer(s) • Relay active"
                diag.scanning -> "Scanning for nearby devices..."
                diag.advertising -> "Broadcasting • Ready to relay"
                else -> "Mesh relay active (offline)"
            }
            val detail = "Peers: $activePeers | Nearby: ${diag.nearbyCount} | Queued: ${diag.pendingOutbound} | Seen: ${diag.seenPackets}\nOffline multi-hop disaster SOS mesh active."
            status to detail
        }
        updateNotification(statusText, detailText)
    }

    private fun updateNotification(statusText: String, detailText: String? = null) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notification = buildNotification(statusText, detailText)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(statusText: String, detailText: String? = null): Notification {
        val openAppIntent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val diagIntent = Intent(this, BleDiagnosticsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val diagPendingIntent = PendingIntent.getActivity(
            this,
            1,
            diagIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = detailText ?: "$statusText\nOffline disaster-response communication enabled phone-to-phone via BLE mesh."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_logo)
            .setContentTitle("GeoRescueX Mesh Relay Active")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppPendingIntent)
            .addAction(
                0,
                "Diagnostics",
                diagPendingIntent
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMeshOperations()
        serviceScope.cancel()

        bluetoothReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            bluetoothReceiver = null
        }
    }

    companion object {
        const val ACTION_START = "com.example.georescux.action.START_BLE_MESH"
        const val ACTION_STOP = "com.example.georescux.action.STOP_BLE_MESH"

        private const val CHANNEL_ID = "georescuex_ble_mesh_service"
        private const val CHANNEL_NAME = "GeoRescueX Mesh Relay"
        private const val CHANNEL_DESC = "Keeps the offline disaster-response mesh network active to relay SOS messages phone-to-phone."
        private const val NOTIFICATION_ID = 7733

        /** Stale device timeout for reconnection: 10 minutes */
        private const val PEER_RECONNECT_WINDOW_MS = 10 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, GeoRescueBleForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GeoRescueBleForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

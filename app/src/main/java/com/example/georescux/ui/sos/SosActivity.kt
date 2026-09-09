package com.example.georescux.ui.sos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.domain.sos.SosLocationStatus
import com.example.georescux.domain.sos.SosState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen SOS flow: countdown first, then the live emergency screen.
 * The flow works offline, and location is best-effort: a missing
 * permission or a missing GPS fix never stops or cancels an emergency.
 * Back is disabled on purpose — only "Stop Emergency" ends an emergency.
 */
class SosActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var viewModel: SosViewModel

    // The permission decision for this emergency is made once; the Grant
    // button can ask again at any time.
    private var locationSetupDone = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            viewModel.beginLocationAcquisition()
        } else {
            viewModel.markPermissionMissing()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sos)

        val container = (application as GeoRescuXApplication).appContainer

        // Ensure BLE Foreground Service is active during an SOS emergency
        runCatching {
            val hasPermissions = com.example.georescux.domain.ble.GeoRescueBlePermissions.missingCritical(
                android.os.Build.VERSION.SDK_INT
            ) { permission ->
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }.isEmpty()
            if (hasPermissions) {
                com.example.georescux.data.ble.GeoRescueBleForegroundService.start(this)
            }
        }

        // Restore an active emergency (e.g. the app was restarted while SOS
        // was running), otherwise begin the countdown armed on the dashboard.
        val initialState =
            if (container.sosRepository.getActiveEmergency() != null) SosState.ACTIVE
            else SosState.COUNTDOWN

        viewModel = ViewModelProvider(
            this,
            SosViewModel.Factory(
                container.startSosUseCase,
                container.cancelSosUseCase,
                container.stopSosUseCase,
                container.sosRepository,
                container.updateSosLocationUseCase,
                container.locationRepository,
                initialState,
            )
        ).get(SosViewModel::class.java)

        // Back is intentionally ignored: an emergency is never abandoned with Back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // No action — use "Stop Emergency" to end the SOS flow.
            }
        })

        // Tap anywhere during the countdown cancels it.
        findViewById<FrameLayout>(R.id.sosRoot).setOnClickListener {
            if (viewModel.uiState.value.state == SosState.COUNTDOWN) {
                viewModel.cancelCountdown()
            }
        }

        findViewById<Button>(R.id.buttonStopEmergency).setOnClickListener {
            viewModel.stopEmergency()
        }

        findViewById<Button>(R.id.buttonGrantLocation).setOnClickListener {
            // The user explicitly asked again for the location permission.
            locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
        }

        uiScope.launch {
            viewModel.uiState.collect { render(it) }
        }

        if (initialState == SosState.COUNTDOWN) {
            viewModel.beginCountdown()
        }
    }

    private fun render(ui: SosUiState) {
        val emergency = ui.emergency
        val countdownContainer = findViewById<LinearLayout>(R.id.countdownContainer)
        val activeContainer = findViewById<LinearLayout>(R.id.activeContainer)
        val countdownText = findViewById<TextView>(R.id.textViewCountdown)
        val stopButton = findViewById<Button>(R.id.buttonStopEmergency)
        val infoText = findViewById<TextView>(R.id.textViewSosInfo)
        val locationStatusText = findViewById<TextView>(R.id.textViewLocationStatus)
        val locationCoordsText = findViewById<TextView>(R.id.textViewLocationCoords)
        val grantButton = findViewById<Button>(R.id.buttonGrantLocation)

        countdownContainer.visibility =
            if (ui.state == SosState.COUNTDOWN) View.VISIBLE else View.GONE
        activeContainer.visibility =
            if (ui.state == SosState.ACTIVE || ui.state == SosState.STOPPING) View.VISIBLE else View.GONE

        when (ui.state) {
            SosState.COUNTDOWN ->
                countdownText.text = (ui.countdownSeconds ?: SosViewModel.COUNTDOWN_SECONDS).toString()

            SosState.ACTIVE, SosState.STOPPING -> {
                stopButton.isEnabled = ui.state == SosState.ACTIVE
                infoText.text = ui.emergency?.let { emergency ->
                    "Started at " + SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(emergency.startedAtMs))
                } ?: ""

                // One permission decision per emergency (per activity lifetime).
                if (!locationSetupDone) {
                    locationSetupDone = true
                    if (hasLocationPermission()) {
                        viewModel.beginLocationAcquisition()
                    } else {
                        // Request only when needed — a denial never stops the SOS.
                        locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
                    }
                }

                renderLocation(emergency?.locationStatus, emergency?.location, locationStatusText, locationCoordsText, grantButton)
            }

            // Countdown cancelled or emergency fully completed -> back to dashboard.
            SosState.IDLE, SosState.COMPLETED -> finish()

            else -> Unit // ARMING never happens on this screen
        }
    }

    private fun renderLocation(
        status: SosLocationStatus?,
        location: com.example.georescux.domain.sos.SosLocation?,
        statusText: TextView,
        coordsText: TextView,
        grantButton: Button,
    ) {
        when (status) {
            SosLocationStatus.ACQUIRED, null -> {
                if (status == SosLocationStatus.ACQUIRED && location != null) {
                    statusText.text = "Location found"
                    coordsText.visibility = View.VISIBLE
                    coordsText.text = (
                        "📍 " + String.format(
                            Locale.US,
                            "%.5f, %.5f (±%.0fm)",
                            location.latitude,
                            location.longitude,
                            location.accuracyMeters
                        ) +
                            " · updated " + SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(Date(location.timestampMs))
                        )
                } else {
                    statusText.text = "Getting location..."
                    coordsText.visibility = View.GONE
                }
                grantButton.visibility = View.GONE
            }

            SosLocationStatus.PERMISSION_MISSING -> {
                statusText.text = "Location permission needed"
                coordsText.visibility = View.GONE
                grantButton.visibility = View.VISIBLE
            }

            SosLocationStatus.UNAVAILABLE -> {
                statusText.text = "Location unavailable"
                coordsText.visibility = View.GONE
                grantButton.visibility = View.GONE
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private companion object {
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}

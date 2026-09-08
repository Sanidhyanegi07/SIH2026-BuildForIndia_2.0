package com.example.georescux.ui.alerts

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only history of completed SOS emergencies, newest first.
 * This screen never creates, modifies, or deletes SOS records.
 */
class AlertsActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alerts)

        val container = (application as GeoRescuXApplication).appContainer
        val viewModel = ViewModelProvider(this, AlertsViewModel.Factory(container.sosRepository))
            .get(AlertsViewModel::class.java)

        val alertsListContainer = findViewById<LinearLayout>(R.id.alertsListContainer)
        val emptyText = findViewById<TextView>(R.id.textViewEmptyAlerts)

        uiScope.launch {
            viewModel.alerts.collect { alerts ->
                emptyText.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
                alertsListContainer.removeAllViews()
                alerts.forEach { alert ->
                    alertsListContainer.addView(alertRow(alert))
                }
            }
        }
    }

    private fun alertRow(alert: SosEmergency): View {
        val row = layoutInflater.inflate(R.layout.item_alert, null, false)
        row.findViewById<TextView>(R.id.textAlertDate).text =
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(alert.startedAtMs))
        row.findViewById<TextView>(R.id.textAlertDuration).text = "Duration: " + formatDuration(alert)
        row.findViewById<TextView>(R.id.textAlertLocation).text = formatLocation(alert)
        return row
    }

    private fun formatDuration(alert: SosEmergency): String {
        val stoppedAt = alert.stoppedAtMs ?: return "ongoing"
        val totalSeconds = ((stoppedAt - alert.startedAtMs) / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private fun formatLocation(alert: SosEmergency): String {
        alert.location?.let { location ->
            return "📍 " + String.format(
                Locale.US,
                "%.4f, %.4f (±%.0fm)",
                location.latitude,
                location.longitude,
                location.accuracyMeters
            )
        }
        return when (alert.locationStatus) {
            SosLocationStatus.PERMISSION_MISSING -> "Location permission needed"
            SosLocationStatus.UNAVAILABLE -> "Location unavailable"
            else -> "No location recorded"
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}

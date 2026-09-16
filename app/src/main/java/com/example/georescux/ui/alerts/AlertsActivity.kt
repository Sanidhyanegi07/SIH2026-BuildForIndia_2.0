package com.example.georescux.ui.alerts

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.data.alerts.AdminAlertObserver
import com.example.georescux.domain.admin.DistrictCatalog
import com.example.georescux.domain.alerts.AlertEntry
import com.example.georescux.domain.alerts.AlertSource
import com.example.georescux.ui.common.ActiveSosBanner
import com.example.georescux.ui.common.BottomNav
import com.example.georescux.ui.common.HelpLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The unified emergency information centre (spec §20): the user's own SOS
 * events, SOS alerts received over the local BLE mesh, and official
 * administrative alerts for the active region — all source-labelled, newest
 * first, and readable offline once stored locally.
 *
 * This screen never creates, modifies, or deletes records.
 */
class AlertsActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val container by lazy { (application as GeoRescuXApplication).appContainer }
    private lateinit var adminAlertObserver: AdminAlertObserver

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    override fun onResume() {
        super.onResume()
        ActiveSosBanner.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alerts)

        HelpLauncher.bind(this)
        findViewById<TextView>(R.id.topBarTitle).setText(R.string.alerts_title)

        adminAlertObserver = AdminAlertObserver(this, { container.activeRegionId }) {
            // Derive the device's district from its most recent located SOS
            // record so a district-scoped admin alert is filtered correctly.
            val located = container.sosRepository.getActiveEmergency()?.location
                ?: container.sosRepository.getHistory()
                    .firstOrNull { it.location != null }?.location
            located?.let { DistrictCatalog.districtForLocation(it.latitude, it.longitude)?.displayName }
        }
        val viewModel = ViewModelProvider(
            this,
            AlertsViewModel.Factory(container.sosRepository, adminAlertObserver.alerts.value),
        ).get(AlertsViewModel::class.java)

        val alertsListContainer = findViewById<LinearLayout>(R.id.alertsListContainer)
        val emptyText = findViewById<TextView>(R.id.textViewEmptyAlerts)

        adminAlertObserver.start()

        // Live administrative alerts flow into the merged feed.
        uiScope.launch {
            adminAlertObserver.alerts.collect { alerts -> viewModel.setAdminAlerts(alerts) }
        }

        uiScope.launch {
            viewModel.allAlerts.collect { alerts ->
                emptyText.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
                alertsListContainer.removeAllViews()
                alerts.forEach { alert ->
                    alertsListContainer.addView(alertRow(alert))
                }
            }
        }

        BottomNav.bind(this, R.id.nav_alerts)
    }

    private fun alertRow(alert: AlertEntry): View {
        val row = layoutInflater.inflate(R.layout.item_alert, null, false)

        val sourceBadge = row.findViewById<TextView>(R.id.textAlertSource)
        sourceBadge.text = alert.source.label
        sourceBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
            sourceColor(alert.source),
        )

        row.findViewById<TextView>(R.id.textAlertDate).text =
            dateFormat.format(Date(alert.timestampMs))

        val titleView = row.findViewById<TextView>(R.id.textAlertTitle)
        if (alert.title.isNotBlank()) {
            titleView.text = alert.title
            titleView.visibility = View.VISIBLE
        }

        val noteView = row.findViewById<TextView>(R.id.textAlertNote)
        val noteText = alert.note?.takeIf { it.isNotBlank() }
            ?: alert.body.takeIf { it.isNotBlank() }
        if (noteText != null) {
            noteView.text = noteText
            noteView.visibility = View.VISIBLE
        }

        row.findViewById<TextView>(R.id.textAlertDuration).text =
            if (alert.source == AlertSource.ADMIN) {
                alert.locationLabel?.let { "Scope: $it" }.orEmpty()
            } else {
                "Duration: ${alert.durationLabel}"
            }

        row.findViewById<TextView>(R.id.textAlertLocation).text = alert.locationLabel
        return row
    }

    private fun sourceColor(source: AlertSource): Int = androidx.core.content.ContextCompat.getColor(
        this,
        when (source) {
            AlertSource.OWN_SOS -> R.color.sos_red
            AlertSource.BLE_SOS -> R.color.hazard_orange
            AlertSource.ADMIN -> R.color.violet_accent
        },
    )

    override fun onDestroy() {
        adminAlertObserver.stop()
        uiScope.cancel()
        super.onDestroy()
    }
}


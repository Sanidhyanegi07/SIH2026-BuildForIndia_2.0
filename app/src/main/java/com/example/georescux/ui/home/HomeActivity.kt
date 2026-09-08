package com.example.georescux.ui.home

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.domain.sos.SosEvent
import com.example.georescux.domain.sos.SosStateMachine
import com.example.georescux.domain.sos.SosState
import com.example.georescux.ui.alerts.AlertsActivity
import com.example.georescux.ui.auth.LoginActivity
import com.example.georescux.ui.contacts.ContactsActivity
import com.example.georescux.ui.routing.RouteActivity
import com.example.georescux.ui.sos.SosActivity
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocationStatus
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

    private val holdHandler = Handler(Looper.getMainLooper())
    private var holdProgressAnimator: ObjectAnimator? = null
    private var holdCompleteRunnable: Runnable? = null
    private var isHolding = false

    // The dashboard's own view of the SOS state machine (arming only).
    // The countdown and emergency states live in SosActivity/SosViewModel.
    private var sosMachineState = SosState.IDLE

    private lateinit var recentActivitySummaryText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val container = (application as GeoRescuXApplication).appContainer
        val authRepository = container.authRepository

        findViewById<TextView>(R.id.textViewUserEmail).text =
            "Logged in as: ${authRepository.currentUserEmail ?: "unknown user"}"

        findViewById<Button>(R.id.buttonLogout).setOnClickListener {
            authRepository.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            // Close Home so that pressing Back does not return to a logged-in screen.
            finish()
        }

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
    }

    private fun setupSosButton() {
        val sosButton = findViewById<Button>(R.id.buttonSos)
        val holdProgress = findViewById<ProgressBar>(R.id.progressBarSosHold)
        val sosHint = findViewById<TextView>(R.id.textViewSosHint)

        sosButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startHold(sosButton, holdProgress, sosHint)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    cancelHold(sosButton, holdProgress, sosHint)
            }
            true // consume the events so the held button never fires a plain click
        }
    }

    private fun startHold(button: Button, progress: ProgressBar, hint: TextView) {
        // If an emergency is already active (e.g. restored after a restart),
        // show it instead of arming a second one.
        if ((application as GeoRescuXApplication).appContainer.sosRepository.getActiveEmergency() != null) {
            openSosScreen()
            return
        }

        isHolding = true
        sosMachineState = SosStateMachine.onEvent(sosMachineState, SosEvent.HOLD_STARTED) // IDLE -> ARMING
        hint.text = "Keep holding..."
        progress.visibility = View.VISIBLE

        holdProgressAnimator = ObjectAnimator.ofInt(progress, "progress", 0, 100).apply {
            duration = HOLD_DURATION_MS
            start()
        }
        holdCompleteRunnable = Runnable {
            isHolding = false
            sosMachineState = SosStateMachine.onEvent(sosMachineState, SosEvent.HOLD_COMPLETED) // ARMING -> COUNTDOWN
            openSosScreen()
            resetHoldUi(button, progress, hint)
        }.also { holdHandler.postDelayed(it, HOLD_DURATION_MS) }
    }

    private fun cancelHold(button: Button, progress: ProgressBar, hint: TextView) {
        if (!isHolding) return // the hold already completed; nothing to cancel
        isHolding = false
        holdCompleteRunnable?.let { holdHandler.removeCallbacks(it) }
        holdProgressAnimator?.cancel()

        // Released early: silent cancel — no SOS record is created.
        sosMachineState = SosStateMachine.onEvent(sosMachineState, SosEvent.HOLD_RELEASED) // ARMING -> IDLE
        resetHoldUi(button, progress, hint)
    }

    private fun resetHoldUi(button: Button, progress: ProgressBar, hint: TextView) {
        holdCompleteRunnable = null
        holdProgressAnimator = null
        progress.progress = 0
        progress.visibility = View.GONE
        hint.text = "Hold 2 seconds to send"
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
        // If an emergency is active (e.g. the app restarted while SOS ran),
        // show the live emergency screen right away.
        if ((application as GeoRescuXApplication).appContainer.sosRepository.getActiveEmergency() != null) {
            openSosScreen()
        }

        // Refresh the latest-alert summary so a newly completed SOS
        // appears here without any unrelated changes.
        refreshRecentActivitySummary()
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

    private companion object {
        const val HOLD_DURATION_MS = 2000L
    }
}

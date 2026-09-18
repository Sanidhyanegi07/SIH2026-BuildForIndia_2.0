package com.example.georescux.ui.common

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.R
import com.example.georescux.ui.sos.SosActivity

/**
 * The global active-SOS banner (spec §21/§72).
 *
 * Once an SOS is active it is a *global* emergency state, not a screen you
 * are stuck on. Every user screen that binds this banner shows a persistent
 * "🚨 SOS ACTIVE" strip with an OPEN action back into the live emergency
 * screen and a × to dismiss the banner (the SOS itself remains active).
 *
 * Screens call [refresh] from onResume (and after any state change) so the
 * strip appears/disappears without the user having to re-enter the screen.
 */
object ActiveSosBanner {

    /**
     * Binds the banner view for this activity and brings it in sync with the
     * current emergency state. Safe to call repeatedly.
     */
    fun refresh(activity: Activity) {
        val banner = activity.findViewById<LinearLayout>(R.id.activeSosBanner) ?: return
        val text = activity.findViewById<TextView>(R.id.activeSosBannerText) ?: return
        val btnClose = activity.findViewById<Button>(R.id.activeSosBannerClose) ?: return
        val btnOpen = activity.findViewById<Button>(R.id.activeSosBannerOpen) ?: return
        val container = (activity.application as? GeoRescuXApplication)?.appContainer
        val active = container?.let {
            runCatching { it.sosRepository.getActiveEmergency() }.getOrNull()
        }

        if (active == null) {
            banner.visibility = View.GONE
            return
        }

        banner.visibility = View.VISIBLE
        // × dismisses the banner (SOS remains active)
        btnClose.setOnClickListener { banner.visibility = View.GONE }
        // OPEN opens the SOS control panel
        btnOpen.setOnClickListener {
            val intent = Intent(activity, SosActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            activity.startActivity(intent)
        }
    }
}

package com.example.georescux.ui.common

import android.app.Activity
import android.content.Intent
import com.example.georescux.R
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * The four-tab primary navigation shared by the user screens (spec §9):
 * HOME · MAP · ALERTS · PROFILE.
 *
 * The app stays activity-based (no destructive rewrite to a single-activity
 * graph), so this helper binds the shared [BottomNavigationView] to whichever
 * screen includes it, marks the current tab, and reorders an existing
 * instance to the front instead of stacking duplicates.
 *
 * Emergency-first rule: SOS is never hidden inside this bar — it stays a
 * prominent control on the Home dashboard, so the nav stays concise.
 */
object BottomNav {

    fun bind(activity: Activity, currentTabId: Int) {
        val view = activity.findViewById<BottomNavigationView>(R.id.bottomNavigationView) ?: return
        view.menu.findItem(currentTabId)?.isChecked = true
        view.setOnItemSelectedListener { item ->
            if (item.itemId == currentTabId) {
                return@setOnItemSelectedListener true
            }
            val target = when (item.itemId) {
                R.id.nav_home -> com.example.georescux.ui.home.HomeActivity::class.java
                R.id.nav_map -> com.example.georescux.ui.map.MapActivity::class.java
                R.id.nav_alerts -> com.example.georescux.ui.alerts.AlertsActivity::class.java
                R.id.nav_profile -> com.example.georescux.ui.profile.ProfileActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            activity.startActivity(intent)
            true
        }
    }
}

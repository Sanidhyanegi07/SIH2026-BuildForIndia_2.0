package com.example.georescux.ui.common

import android.app.Activity
import android.content.Intent
import android.view.View
import com.example.georescux.R
import com.example.georescux.ui.help.HelpActivity

/**
 * Wires the global top-right help icon (spec §7 / §31). Any screen that
 * includes the shared help button gets the full Help Center in one call.
 */
object HelpLauncher {

    fun bind(activity: Activity) {
        activity.findViewById<View>(R.id.helpButton)?.setOnClickListener {
            activity.startActivity(Intent(activity, HelpActivity::class.java))
        }
    }
}

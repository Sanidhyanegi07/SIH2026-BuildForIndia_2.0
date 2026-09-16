package com.example.georescux.ui.common

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.georescux.R

/**
 * State-aware connection indicator (spec §8).
 *
 * Shows ONLINE only when the cloud sync layer is genuinely reachable, and an
 * OFFLINE state that makes clear the app has NOT stopped working — local
 * data, offline maps and SOS stay active and only cloud sync is pending. The
 * app must never imply it is unusable just because the internet is gone.
 */
class ConnectivityBadge(private val activity: Activity) {

    var isOnline: Boolean = false
        private set

    /** Fired on the main thread whenever the connectivity state changes. */
    var onChange: ((online: Boolean) -> Unit)? = null

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var view: TextView? = null
    private var initialized = false

    fun bind(textView: TextView) {
        view = textView
        render()
        register()
    }

    fun unbind() {
        callback?.let { cb ->
            try {
                activity.getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        callback = null
    }

    private fun register() {
        if (callback != null) return
        val manager = activity.getSystemService(ConnectivityManager::class.java) ?: return
        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()

            override fun onLost(network: Network) = refresh()
        }
        try {
            manager.registerDefaultNetworkCallback(newCallback)
            callback = newCallback
        } catch (_: Exception) {
            // The badge still reflects state at the next refresh; never crash.
        }
    }

    private fun refresh() {
        activity.runOnUiThread { render() }
    }

    private fun render() {
        val textView = view ?: return
        val manager = activity.getSystemService(ConnectivityManager::class.java)
        val online = try {
            val network = manager?.activeNetwork
            val capabilities = network?.let { manager.getNetworkCapabilities(it) }
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true ||
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Exception) {
            false
        }

        if (online == isOnline && initialized) {
            return // No change: avoid flapping the UI on redundant callbacks.
        }
        isOnline = online
        initialized = true

        if (online) {
            textView.text = activity.getString(R.string.connection_online)
            textView.setTextColor(ContextCompat.getColor(activity, R.color.safe_green))
        } else {
            textView.text = activity.getString(R.string.connection_offline)
            textView.setTextColor(ContextCompat.getColor(activity, R.color.hazard_orange))
        }
        onChange?.invoke(online)
    }
}

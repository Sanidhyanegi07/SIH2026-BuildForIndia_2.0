package com.example.georescux.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * [ConnectivityMonitor] backed by the framework ConnectivityManager
 * (Stage 7B-3 Step 4 — no new dependencies). Requires the
 * ACCESS_NETWORK_STATE permission.
 *
 * The callback fires whenever the system gains a default network. Note
 * the system may also deliver one onAvailable right after registration
 * when a network is already present; the retry run that follows simply
 * no-ops when nothing is pending, so no unnecessary cloud work happens.
 *
 * The registration lives for the whole process lifetime; [stop] releases
 * it (used on the Application's teardown path and never leaking the
 * callback object).
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onRegained: () -> Unit) {
        // Belt-and-braces against double registration (the coordinator
        // already guards repeated starts).
        if (callback != null) return
        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Called on a system handler thread: the coordinator hands
                // the work to its own coroutine scope, so this never blocks.
                onRegained()
            }
        }
        callback = newCallback
        // minSdk 24: registerDefaultNetworkCallback exists on every
        // supported API level.
        connectivityManager.registerDefaultNetworkCallback(newCallback)
    }

    override fun stop() {
        val current = callback ?: return
        callback = null
        connectivityManager.unregisterNetworkCallback(current)
    }
}

package com.example.georescux.data.sync

/**
 * Registers a process-level trigger that fires when usable network
 * connectivity becomes available (Stage 7B-3 Step 4). Abstracted so the
 * retry wiring is JVM-testable; [AndroidConnectivityMonitor] wraps the
 * framework ConnectivityManager. No third-party networking is involved.
 */
interface ConnectivityMonitor {
    /**
     * Starts delivering connectivity-regained events to [onRegained].
     * Implementations must tolerate [start] being called repeatedly
     * without registering a second callback.
     */
    fun start(onRegained: () -> Unit)

    /** Stops delivery and releases the underlying registration. */
    fun stop()
}

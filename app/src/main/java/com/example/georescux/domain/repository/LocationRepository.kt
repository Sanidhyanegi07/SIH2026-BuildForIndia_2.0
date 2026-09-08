package com.example.georescux.domain.repository

import com.example.georescux.domain.sos.SosLocation

/**
 * Location acquisition for the SOS flow. Implemented in the data layer
 * with the Android location APIs; the domain layer never sees them.
 *
 * The implementation must use the application context and must stop all
 * updates when [stopAcquisition] is called.
 */
interface LocationRepository {
    /**
     * Begins best-effort acquisition and delivers every fix on the main
     * thread. The caller is responsible for having the location permission
     * and for calling [stopAcquisition] when done.
     */
    fun startAcquisition(onLocation: (SosLocation) -> Unit)

    /** Stops all location updates. Safe to call multiple times. */
    fun stopAcquisition()
}

package com.example.georescux.domain.repository

import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus

/**
 * SOS operations the rest of the app can use. Phase 1 is fully local
 * (works offline); Firebase sync will be added later behind this interface.
 */
interface SosRepository {
    /** The currently active emergency, or null. Survives app restarts. */
    fun getActiveEmergency(): SosEmergency?

    /**
     * Persists a new active emergency. Returns null when an emergency is
     * already active — there can never be two simultaneous emergencies.
     */
    fun startEmergency(id: String, startedAtMs: Long): SosEmergency?

    /** Marks the active emergency completed and moves it to history. */
    fun completeEmergency(stoppedAtMs: Long): SosEmergency?

    /**
     * Updates the location of the currently active emergency (no-op when
     * none is active). Updates the existing record — never creates a new
     * emergency or a history entry.
     */
    fun updateActiveEmergencyLocation(location: SosLocation?, status: SosLocationStatus)

    /** Finished emergencies, newest first. */
    fun getHistory(): List<SosEmergency>
}

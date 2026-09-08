package com.example.georescux.data.sos

import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus

/**
 * Local-first SOS repository. Everything is stored on the device, which is
 * why the flow works offline. Firebase sync will be added later behind the
 * same [SosRepository] interface.
 */
class SosRepositoryImpl(private val store: LocalSosStore) : SosRepository {

    override fun getActiveEmergency(): SosEmergency? = store.loadActive()

    override fun startEmergency(id: String, startedAtMs: Long): SosEmergency? {
        // Safety rule: never allow two simultaneous emergencies.
        if (store.loadActive() != null) return null
        val emergency = SosEmergency(id = id, startedAtMs = startedAtMs)
        store.saveActive(emergency)
        return emergency
    }

    override fun completeEmergency(stoppedAtMs: Long): SosEmergency? {
        val active = store.loadActive() ?: return null
        val completed = active.copy(stoppedAtMs = stoppedAtMs)
        store.clearActive()
        store.addToHistory(completed)
        return completed
    }

    override fun updateActiveEmergencyLocation(location: SosLocation?, status: SosLocationStatus) {
        // Updates the existing record in place — never creates a new
        // emergency or a history entry.
        val active = store.loadActive() ?: return
        store.saveActive(active.copy(location = location, locationStatus = status))
    }

    override fun getHistory(): List<SosEmergency> = store.loadHistory()
}

package com.example.georescux.data.sos

import com.example.georescux.domain.incident.IncidentMapper
import com.example.georescux.domain.relay.RelayEngine
import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus

/**
 * Decorator over [SosRepository] that forwards locally activated SOS emergencies
 * into the BLE mesh via [RelayEngine].
 *
 * Bug 2 + 7 fix: previously EVERY location update called [RelayEngine.onLocalEvent] with a
 * freshly generated eventId. This flooded the mesh with hundreds of unique events per SOS
 * session, filled [RelayEngine.seenEventIds], and caused peers to deduplicate out the
 * original SOS alert. Now only two events cross the relay per emergency:
 *   1. On [startEmergency] — the initial SOS alert peers need to act on.
 *   2. On [completeEmergency] — so peers can mark the emergency as resolved.
 *
 * Location updates are NOT relayed: they are best-effort enhancements that live only in the
 * local store and in cloud backup; nearby peers already know an SOS is active via (1).
 */
class SosRelayRepository(
    private val delegate: SosRepository,
    private val relayEngine: RelayEngine,
    private val selfOriginId: String,
) : SosRepository {

    override fun getActiveEmergency(): SosEmergency? = delegate.getActiveEmergency()

    override fun getHistory(): List<SosEmergency> = delegate.getHistory()

    override fun startEmergency(id: String, startedAtMs: Long): SosEmergency? {
        val emergency = delegate.startEmergency(id, startedAtMs)
        if (emergency != null) {
            // Relay once: peers need to know an SOS is active.
            val incident = IncidentMapper.fromSos(
                emergency = emergency,
                originId = selfOriginId,
                pendingCloudBackup = true,
            )
            relayEngine.onLocalEvent(incident)
        }
        return emergency
    }

    override fun updateActiveEmergencyLocation(location: SosLocation?, status: SosLocationStatus) {
        // FIX Bug 2 + 7: do NOT relay location updates.
        // Each update used to create a NEW eventId, flooding the mesh and breaking dedup.
        // Nearby peers already know an SOS is active from the startEmergency relay.
        delegate.updateActiveEmergencyLocation(location, status)
    }

    override fun completeEmergency(stoppedAtMs: Long): SosEmergency? {
        val completed = delegate.completeEmergency(stoppedAtMs)
        if (completed != null) {
            // Relay once: peers can now mark the emergency resolved.
            val incident = IncidentMapper.fromSos(
                emergency = completed,
                originId = selfOriginId,
                pendingCloudBackup = false,
            )
            relayEngine.onLocalEvent(incident)
        }
        return completed
    }
}

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
        delegate.updateActiveEmergencyLocation(location, status)
        val active = delegate.getActiveEmergency()
        if (active != null) {
            val incident = IncidentMapper.fromSos(
                emergency = active,
                originId = selfOriginId,
                pendingCloudBackup = true,
            )
            relayEngine.onLocalEvent(incident)
        }
    }

    override fun completeEmergency(stoppedAtMs: Long): SosEmergency? {
        return delegate.completeEmergency(stoppedAtMs)
    }
}

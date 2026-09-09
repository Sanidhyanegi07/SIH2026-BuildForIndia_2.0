package com.example.georescux.data.sos

import com.example.georescux.data.ble.BleSosBridge
import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus

/**
 * Decorator over the existing SOS repository chain that ALSO hands each
 * local SOS transition to the raw BLE subsystem ([BleSosBridge]).
 *
 * Placement in the chain:
 *   SosBleBridgeRepository -> SosRelayRepository (Nearby mesh) ->
 *   SyncingSosRepository (local write FIRST, Firebase backup) -> local store
 *
 * Guarantees:
 * - the local SOS record is written by the delegate exactly as before;
 * - BLE enqueue is fire-and-forget and exception-contained: a BLE failure
   can NEVER cancel, delay or block SOS activation;
 * - no existing Firebase/offline-sync behavior is changed or duplicated.
 */
class SosBleBridgeRepository(
    private val delegate: SosRepository,
    private val bridge: BleSosBridge,
) : SosRepository {

    private var lastEmittedHasLocation = false

    override fun getActiveEmergency(): SosEmergency? = delegate.getActiveEmergency()

    override fun getHistory(): List<SosEmergency> = delegate.getHistory()

    override fun startEmergency(id: String, startedAtMs: Long): SosEmergency? {
        val emergency = delegate.startEmergency(id, startedAtMs)
        lastEmittedHasLocation = emergency?.location != null
        if (emergency != null) {
            bridge.publishSos(emergency, emergencyId = id)
        }
        return emergency
    }

    override fun updateActiveEmergencyLocation(location: SosLocation?, status: SosLocationStatus) {
        delegate.updateActiveEmergencyLocation(location, status)
        if (location != null && !lastEmittedHasLocation) {
            val active = delegate.getActiveEmergency()
            if (active != null) {
                lastEmittedHasLocation = true
                bridge.publishSos(active, emergencyId = active.id)
            }
        }
    }

    override fun completeEmergency(stoppedAtMs: Long): SosEmergency? {
        val completed = delegate.completeEmergency(stoppedAtMs)
        lastEmittedHasLocation = false
        if (completed != null) {
            bridge.publishSos(completed, emergencyId = completed.id)
        }
        return completed
    }
}

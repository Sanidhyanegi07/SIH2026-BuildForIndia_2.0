package com.example.georescux.data.ble

import com.example.georescux.domain.ble.GeoRescueBleDiagnostics
import com.example.georescux.domain.ble.GeoRescueBlePacket
import com.example.georescux.domain.ble.GeoRescueBlePacketType
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation

/**
 * The SOS <-> BLE bridge (Phase 9).
 *
 * Send side: converts a locally activated/completed [SosEmergency] into a
 * [GeoRescueBlePacket] and hands it to [GeoRescueBleManager.publishPacket].
 * The packet carries ONLY minimum emergency data: id, timestamps, location,
 * status — never credentials, tokens or personal information.
 *
 * Receive side: converts a received SOS packet back into a [SosEmergency]
 * for the app layer. The AppContainer listener persists it into the
 * existing local SOS store, where the EXISTING sync engine picks it up and
 * uploads it to Firebase when that device has Internet (Phase 10) — no
 * second sync system.
 *
 * Critical rule honored here: BLE failure must NEVER cancel or block SOS
 * activation. Every entry point is fire-and-forget and exception-contained.
 */
class BleSosBridge(
    private val manager: GeoRescueBleManager?,
    private val originDeviceId: String,
) {

    /**
     * Builds the wire packet for an SOS emergency.
     * [emergencyId] is the packet's originEmergencyId (the local record id).
     */
    fun buildSosPacket(emergency: SosEmergency, emergencyId: String): GeoRescueBlePacket =
        GeoRescueBlePacket(
            packetId = GeoRescueBlePacket.newPacketId(),
            originDeviceId = originDeviceId,
            originEmergencyId = emergencyId,
            timestampMs = emergency.startedAtMs,
            ttl = GeoRescueBlePacket.DEFAULT_TTL_HOPS,
            type = GeoRescueBlePacketType.SOS,
            latitude = emergency.location?.latitude,
            longitude = emergency.location?.longitude,
            status = if (emergency.isActive) SOS_STATUS_ACTIVE else SOS_STATUS_COMPLETED,
            payload = buildMap {
                emergency.locationStatus?.let { put("locationStatus", it.name) }
            },
        )

    /**
     * Publishes an SOS packet over BLE. Fire-and-forget: returns false (not
     * throws) for any failure — SOS activation is never blocked by BLE.
     */
    fun publishSos(emergency: SosEmergency, emergencyId: String): Boolean {
        val bleManager = manager ?: return false
        return try {
            val packet = buildSosPacket(emergency, emergencyId)
            val decision = bleManager.publishPacket(packet)
            GeoRescueBleDiagnostics.info(
                GeoRescueBleDiagnostics.SOS_PACKET_ENQUEUED,
                "packetId=${packet.packetId} emergencyId=$emergencyId decision=$decision"
            )
            true
        } catch (e: Exception) {
            // Contained by design: BLE failure never breaks SOS.
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_ERROR,
                "code=SOS_PUBLISH_FAILED detail=${e.message}"
            )
            false
        }
    }

    /**
     * Converts a received SOS packet into the existing SOS model. Returns
     * null for non-SOS packets. The id is prefixed MESH_BLE_ so received
     * relays never collide with locally created records.
     */
    fun emergencyFromPacket(packet: GeoRescueBlePacket): SosEmergency? {
        if (packet.type != GeoRescueBlePacketType.SOS) return null
        val location = if (packet.latitude != null && packet.longitude != null) {
            SosLocation(
                latitude = packet.latitude,
                longitude = packet.longitude,
                accuracyMeters = 0f,
                timestampMs = packet.timestampMs,
                provider = "ble_relay",
            )
        } else {
            null
        }
        val status = packet.status ?: SOS_STATUS_ACTIVE
        return SosEmergency(
            id = "MESH_BLE_${packet.originDeviceId}_${packet.packetId}",
            startedAtMs = packet.timestampMs,
            stoppedAtMs = if (status == SOS_STATUS_COMPLETED) packet.timestampMs + 1000L else null,
            location = location,
            locationStatus = location?.let {
                runCatching {
                    com.example.georescux.domain.sos.SosLocationStatus.valueOf(
                        packet.payload["locationStatus"] ?: "ACQUIRED"
                    )
                }.getOrNull() ?: com.example.georescux.domain.sos.SosLocationStatus.ACQUIRED
            },
        )
    }

    companion object {
        const val SOS_STATUS_ACTIVE = "ACTIVE"
        const val SOS_STATUS_COMPLETED = "COMPLETED"
    }
}

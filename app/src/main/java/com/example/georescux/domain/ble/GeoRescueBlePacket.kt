package com.example.georescux.domain.ble

import com.example.georescux.data.maps.MiniJson
import java.util.UUID

/**
 * The structured GeoRescuX emergency packet carried over the raw BLE GATT
 * transport. BLE never carries arbitrary strings — every payload that goes
 * over the air is one serialized [GeoRescueBlePacket].
 *
 * Wire form (JSON, UTF-8, newline-terminated frame):
 * {
 *   "protocolVersion": 1,
 *   "packetId": "GRX-...",            // globally unique per emission
 *   "originDeviceId": "DEVICE_...",   // stable sender identity
 *   "originEmergencyId": "...",       // the SOS record this packet belongs to
 *   "timestamp": 1690000000000,
 *   "ttl": 5,                         // hops remaining, decremented per forward
 *   "type": "SOS" | "GEORESCUEX_BLE_TEST",
 *   "latitude": 30.7333,              // optional
 *   "longitude": 76.7794,             // optional
 *   "status": "ACTIVE",               // optional, SOS lifecycle state
 *   "payload": { ... }                // optional string->string extras
 * }
 *
 * Security posture: the packet carries the MINIMUM emergency data required —
 * never credentials, tokens, or personal information (see docs/BLE_SUBSYSTEM.md).
 */
data class GeoRescueBlePacket(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val packetId: String,
    val originDeviceId: String,
    val originEmergencyId: String? = null,
    val timestampMs: Long,
    val ttl: Int = DEFAULT_TTL_HOPS,
    val type: GeoRescueBlePacketType,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val status: String? = null,
    val payload: Map<String, String> = emptyMap(),
) {

    /** A packet with no hops left must be processed locally but never forwarded again. */
    fun hasHopsRemaining(): Boolean = ttl > 0

    /** The forwardable copy: TTL decremented by exactly one hop. */
    fun forNextHop(): GeoRescueBlePacket = copy(ttl = ttl - 1)

    fun serialize(): String = GeoRescueBlePacketCodec.serialize(this)

    companion object {
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_TTL_HOPS = 5

        /** Upper bound accepted from the wire; anything above this is malformed. */
        const val MAX_TTL_HOPS = 32

        /** Maximum serialized packet size accepted on the wire. */
        const val MAX_PACKET_BYTES = 2048

        /** Maximum character length of a single payload entry. */
        const val MAX_PAYLOAD_VALUE_CHARS = 1024

        fun newPacketId(): String = "GRX-${UUID.randomUUID()}"
    }
}

/**
 * Packet types. [SOS] carries emergency relay data; [TEST] is the
 * two-device diagnostic packet identified on the wire as
 * GEORESCUEX_BLE_TEST; [SYNC_INVENTORY] and [SYNC_REQUEST] provide
 * lightweight peer state reconciliation.
 */
enum class GeoRescueBlePacketType(val wireName: String) {
    SOS("SOS"),
    TEST("GEORESCUEX_BLE_TEST"),
    SYNC_INVENTORY("GEORESCUEX_BLE_SYNC_INV"),
    SYNC_REQUEST("GEORESCUEX_BLE_SYNC_REQ");

    companion object {
        const val TEST_PACKET_MARKER = "GEORESCUEX_BLE_TEST"

        fun fromWireName(name: String): GeoRescueBlePacketType? =
            entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Structural validation verdicts. Every distinct failure is named so the
 * diagnostic log can report WHY a packet was rejected.
 */
enum class GeoRescueBlePacketVerdict {
    VALID,
    MALFORMED_PROTOCOL_VERSION,
    MALFORMED_PACKET_ID,
    MALFORMED_ORIGIN_DEVICE,
    MALFORMED_TIMESTAMP,
    MALFORMED_TTL,
    MALFORMED_TYPE,
    MALFORMED_LOCATION,
    MALFORMED_PAYLOAD,
    MALFORMED_SIZE,
}

/**
 * Serializes and deserializes [GeoRescueBlePacket]s. Uses the project's pure
 * Kotlin [MiniJson] parser (same choice as RelayEnvelopeSerializer) so local
 * JVM unit tests run without org.json stub exceptions.
 */
object GeoRescueBlePacketCodec {

    fun serialize(packet: GeoRescueBlePacket): String = buildString {
        append('{')
        append("\"protocolVersion\":").append(packet.protocolVersion).append(',')
        append("\"packetId\":\"").append(escape(packet.packetId)).append("\",")
        append("\"originDeviceId\":\"").append(escape(packet.originDeviceId)).append("\",")
        if (packet.originEmergencyId != null) {
            append("\"originEmergencyId\":\"").append(escape(packet.originEmergencyId)).append("\",")
        }
        append("\"timestamp\":").append(packet.timestampMs).append(',')
        append("\"ttl\":").append(packet.ttl).append(',')
        append("\"type\":\"").append(packet.type.wireName).append("\",")
        if (packet.latitude != null) append("\"latitude\":").append(packet.latitude).append(',')
        if (packet.longitude != null) append("\"longitude\":").append(packet.longitude).append(',')
        if (packet.status != null) {
            append("\"status\":\"").append(escape(packet.status)).append("\",")
        }
        append("\"payload\":{")
        append(packet.payload.entries.joinToString(",") { (k, v) ->
            "\"${escape(k)}\":\"${escape(v)}\""
        })
        append('}')
        append('}')
    }

    /** Returns null for any structurally invalid JSON — never throws. */
    fun deserialize(serialized: String): GeoRescueBlePacket? = try {
        val root = MiniJson.parse(serialized) as? Map<*, *> ?: return null

        val packetId = (root["packetId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val originDeviceId = (root["originDeviceId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val timestampMs = (root["timestamp"] as? Number)?.toLong() ?: return null
        val type = GeoRescueBlePacketType.fromWireName((root["type"] as? String) ?: return null)
            ?: return null

        val payload = mutableMapOf<String, String>()
        (root["payload"] as? Map<*, *>)?.forEach { (k, v) ->
            if (k is String && v != null) payload[k] = v.toString()
        }

        GeoRescueBlePacket(
            protocolVersion = (root["protocolVersion"] as? Number)?.toInt() ?: GeoRescueBlePacket.PROTOCOL_VERSION,
            packetId = packetId,
            originDeviceId = originDeviceId,
            originEmergencyId = root["originEmergencyId"] as? String,
            timestampMs = timestampMs,
            ttl = (root["ttl"] as? Number)?.toInt() ?: GeoRescueBlePacket.DEFAULT_TTL_HOPS,
            type = type,
            latitude = (root["latitude"] as? Number)?.toDouble(),
            longitude = (root["longitude"] as? Number)?.toDouble(),
            status = root["status"] as? String,
            payload = payload,
        )
    } catch (_: Exception) {
        null
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}

/**
 * Pure structural validation of packets (wire-independent). The mesh node
 * validates BEFORE persisting, processing or forwarding a packet.
 */
object GeoRescueBlePacketValidator {

    fun validate(packet: GeoRescueBlePacket): GeoRescueBlePacketVerdict {
        if (packet.protocolVersion != GeoRescueBlePacket.PROTOCOL_VERSION) {
            return GeoRescueBlePacketVerdict.MALFORMED_PROTOCOL_VERSION
        }
        if (packet.packetId.isBlank()) return GeoRescueBlePacketVerdict.MALFORMED_PACKET_ID
        if (packet.originDeviceId.isBlank()) return GeoRescueBlePacketVerdict.MALFORMED_ORIGIN_DEVICE
        if (packet.timestampMs <= 0) return GeoRescueBlePacketVerdict.MALFORMED_TIMESTAMP
        if (packet.ttl < 0 || packet.ttl > GeoRescueBlePacket.MAX_TTL_HOPS) {
            return GeoRescueBlePacketVerdict.MALFORMED_TTL
        }
        val lat = packet.latitude
        val lon = packet.longitude
        if ((lat == null) != (lon == null)) return GeoRescueBlePacketVerdict.MALFORMED_LOCATION
        lat?.let { if (it < -90.0 || it > 90.0) return GeoRescueBlePacketVerdict.MALFORMED_LOCATION }
        lon?.let { if (it < -180.0 || it > 180.0) return GeoRescueBlePacketVerdict.MALFORMED_LOCATION }
        packet.payload.forEach { (key, value) ->
            if (key.isBlank() || value.length > GeoRescueBlePacket.MAX_PAYLOAD_VALUE_CHARS) {
                return GeoRescueBlePacketVerdict.MALFORMED_PAYLOAD
            }
        }
        return GeoRescueBlePacketVerdict.VALID
    }
}

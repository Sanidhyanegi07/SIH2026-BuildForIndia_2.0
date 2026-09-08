package com.example.georescux.data.relay

import com.example.georescux.data.maps.MiniJson
import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentSeverity
import com.example.georescux.domain.incident.IncidentSyncState
import com.example.georescux.domain.incident.IncidentType
import com.example.georescux.domain.relay.RelayEnvelope

/**
 * Encodes and decodes [RelayEnvelope] instances to and from JSON strings for BLE transmission.
 * Uses pure Kotlin parsing ([MiniJson]) so local JVM unit tests pass without org.json stub exceptions.
 */
object RelayEnvelopeSerializer {

    fun serialize(envelope: RelayEnvelope): String {
        val event = envelope.event
        val payloadPairs = event.payload.entries.joinToString(",") { (k, v) ->
            "\"${escape(k)}\":\"${escape(v)}\""
        }

        val eventJson = buildString {
            append('{')
            append("\"eventId\":\"").append(escape(event.eventId)).append("\",")
            append("\"originId\":\"").append(escape(event.originId)).append("\",")
            append("\"type\":\"").append(event.type.name).append("\",")
            append("\"occurredAtMs\":").append(event.occurredAtMs).append(',')
            if (event.latitude != null) append("\"latitude\":").append(event.latitude).append(',')
            if (event.longitude != null) append("\"longitude\":").append(event.longitude).append(',')
            append("\"severity\":\"").append(event.severity.name).append("\",")
            append("\"ttlSeconds\":").append(event.ttlSeconds).append(',')
            append("\"sequence\":").append(event.sequence).append(',')
            if (event.signature != null) append("\"signature\":\"").append(escape(event.signature)).append("\",")
            append("\"syncState\":\"").append(event.syncState.name).append("\",")
            append("\"payload\":{").append(payloadPairs).append("}")
            append('}')
        }

        return buildString {
            append('{')
            append("\"event\":").append(eventJson).append(',')
            append("\"payload\":\"").append(escape(envelope.payload)).append("\"")
            if (envelope.fromPeerId != null) {
                append(",\"fromPeerId\":\"").append(escape(envelope.fromPeerId)).append("\"")
            }
            append('}')
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun deserialize(serialized: String, receivingPeerId: String? = null): RelayEnvelope? {
        return try {
            val root = MiniJson.parse(serialized) as? Map<String, Any?> ?: return null
            val eventObj = root["event"] as? Map<String, Any?> ?: return null

            val payloadMap = mutableMapOf<String, String>()
            val rawPayloadObj = eventObj["payload"] as? Map<String, Any?>
            rawPayloadObj?.forEach { (k, v) ->
                if (v != null) payloadMap[k] = v.toString()
            }

            val event = IncidentEvent(
                eventId = eventObj["eventId"] as? String ?: return null,
                originId = eventObj["originId"] as? String ?: return null,
                type = IncidentType.valueOf(eventObj["type"] as? String ?: return null),
                occurredAtMs = (eventObj["occurredAtMs"] as? Number)?.toLong() ?: return null,
                latitude = (eventObj["latitude"] as? Number)?.toDouble(),
                longitude = (eventObj["longitude"] as? Number)?.toDouble(),
                severity = IncidentSeverity.valueOf((eventObj["severity"] as? String) ?: "MEDIUM"),
                ttlSeconds = (eventObj["ttlSeconds"] as? Number)?.toLong() ?: IncidentEvent.DEFAULT_TTL_SECONDS,
                sequence = (eventObj["sequence"] as? Number)?.toLong() ?: 0L,
                signature = eventObj["signature"] as? String,
                syncState = IncidentSyncState.valueOf((eventObj["syncState"] as? String) ?: "LOCAL_ONLY"),
                payload = payloadMap,
            )

            val payloadString = root["payload"] as? String ?: event.canonicalString()
            val fromPeerId = root["fromPeerId"] as? String ?: receivingPeerId

            RelayEnvelope(
                event = event,
                payload = payloadString,
                fromPeerId = fromPeerId,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}

package com.example.georescux.domain.hazard

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentSeverity
import com.example.georescux.domain.incident.IncidentSyncState
import com.example.georescux.domain.incident.IncidentType
import com.example.georescux.domain.relay.RelayDecision
import com.example.georescux.domain.relay.RelayEngine
import java.util.UUID

/**
 * Use case to submit a user-reported hazard observation (e.g. road blocked, flooding, debris)
 * into the BLE mesh relay system.
 */
class ReportHazardUseCase(
    private val relayEngine: RelayEngine,
    private val selfOriginId: String,
) {

    fun report(
        description: String,
        latitude: Double,
        longitude: Double,
        type: IncidentType = IncidentType.HAZARD,
        severity: IncidentSeverity = IncidentSeverity.HIGH,
        fromNodeId: String? = null,
        toNodeId: String? = null,
        eventId: String = UUID.randomUUID().toString(),
        occurredAtMs: Long = System.currentTimeMillis(),
    ): RelayDecision {
        require(type == IncidentType.HAZARD || type == IncidentType.ROUTE_BLOCK) {
            "ReportHazardUseCase only accepts HAZARD or ROUTE_BLOCK incident types."
        }

        val payloadMap = buildMap {
            put("description", description)
            put("reportedBy", selfOriginId)
            if (fromNodeId != null) put("fromNodeId", fromNodeId)
            if (toNodeId != null) put("toNodeId", toNodeId)
        }

        val event = IncidentEvent(
            eventId = eventId,
            originId = selfOriginId,
            type = type,
            occurredAtMs = occurredAtMs,
            latitude = latitude,
            longitude = longitude,
            severity = severity,
            ttlSeconds = IncidentEvent.DEFAULT_TTL_SECONDS,
            syncState = IncidentSyncState.LOCAL_ONLY,
            payload = payloadMap,
        )

        return relayEngine.onLocalEvent(event)
    }
}

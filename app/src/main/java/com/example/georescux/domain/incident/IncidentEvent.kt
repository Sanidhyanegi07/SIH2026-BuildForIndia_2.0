package com.example.georescux.domain.incident

import com.example.georescux.domain.sos.SosEmergency

/**
 * The single event/incident contract shared by triage, relay, responder
 * views and future reconciliation (approved GeoRescuX design). Existing
 * SOS persistence is NOT replaced — [IncidentMapper] derives incidents
 * from it.
 *
 * [signature] is Base64 over [canonicalString] (kept as String so the
 * data-class equals/hashCode stay structural). [syncState] is deliberately
 * NOT part of the canonical form: it is local delivery metadata that
 * changes as the event moves, while the signature covers the event content.
 */
data class IncidentEvent(
    val eventId: String,
    val originId: String,
    val type: IncidentType,
    val occurredAtMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val severity: IncidentSeverity = IncidentSeverity.MEDIUM,
    val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    val sequence: Long = 0L, // 0 = unordered (no per-origin sequencing yet)
    val signature: String? = null,
    val syncState: IncidentSyncState = IncidentSyncState.LOCAL_ONLY,
    val payload: Map<String, String> = emptyMap(),
) {
    /**
     * Deterministic byte-source for signing and relay payloads. Every field
     * that must be tamper-proof is included; signature and syncState are
     * excluded by definition. Payload entries are sorted so equal events
     * canonicalize identically.
     */
    fun canonicalString(): String = buildString {
        append(eventId).append('|')
        append(originId).append('|')
        append(type.name).append('|')
        append(occurredAtMs).append('|')
        append(latitude ?: "").append('|')
        append(longitude ?: "").append('|')
        append(severity.name).append('|')
        append(ttlSeconds).append('|')
        append(sequence)
        payload.toSortedMap().forEach { (key, value) ->
            append('|').append(key).append('=').append(value)
        }
    }

    companion object {
        const val DEFAULT_TTL_SECONDS = 3_600L // relay propagation stops after 1 h
    }
}

enum class IncidentType { SOS, HAZARD, ROUTE_BLOCK, SHELTER_STATUS, ALERT, ACK }

enum class IncidentSeverity { CRITICAL, HIGH, MEDIUM, LOW }

/**
 * Explicit freshness/delivery state for an incident. ONE source of truth
 * per incident (for SOS it is derived from the durable SyncStateStore
 * markers): LOCAL_ONLY (never left the device), QUEUED (waiting to leave),
 * RELAYED (forwarded to a peer), SYNCING (cloud push in flight), SYNCED
 * (confirmed at the cloud copy), CONFLICT (reserved for reconciliation).
 */
enum class IncidentSyncState { LOCAL_ONLY, QUEUED, RELAYED, SYNCING, SYNCED, CONFLICT }

/**
 * Derives [IncidentEvent]s from the existing SOS model without touching
 * SOS persistence. The sync state is honest: it reflects the durable
 * pending markers, so the UI can never imply a cloud copy that does not
 * exist.
 */
object IncidentMapper {

    fun fromSos(
        emergency: SosEmergency,
        originId: String,
        pendingCloudBackup: Boolean,
    ): IncidentEvent = IncidentEvent(
        eventId = emergency.id,
        originId = originId,
        type = IncidentType.SOS,
        occurredAtMs = emergency.startedAtMs,
        latitude = emergency.location?.latitude,
        longitude = emergency.location?.longitude,
        severity = IncidentSeverity.CRITICAL,
        sequence = 0L,
        syncState = if (pendingCloudBackup) IncidentSyncState.QUEUED else IncidentSyncState.SYNCED,
        payload = buildMap {
            put("status", if (emergency.stoppedAtMs == null) "ACTIVE" else "COMPLETED")
            emergency.locationStatus?.let { put("locationStatus", it.name) }
        },
    )
}

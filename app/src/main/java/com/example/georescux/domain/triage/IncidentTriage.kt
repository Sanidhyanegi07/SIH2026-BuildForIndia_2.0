package com.example.georescux.domain.triage

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentSeverity
import com.example.georescux.domain.incident.IncidentType

/**
 * Explainable on-device incident triage — a DETERMINISTIC RULESET, not a
 * model: identical input always produces identical severity, confidence
 * and reason codes, and every result explains itself through
 * [TriageResult.reasonCodes]. Safety-critical decisions elsewhere
 * (routing) remain deterministic.
 *
 * Corroboration is transparent: N independent reports raise confidence by
 * 0.1 each (capped at 1.0) and add an explicit reason code. Nothing is
 * auto-trusted from a single report.
 */
data class TriageResult(
    val severity: IncidentSeverity,
    val confidence: Double,
    val reasonCodes: List<String>,
)

object IncidentTriage {

    fun triage(event: IncidentEvent, independentReports: Int = 0): TriageResult {
        if (independentReports < 0) {
            return TriageResult(IncidentSeverity.MEDIUM, 0.5, listOf("fallback:invalid-corroboration"))
        }
        val base = when (event.type) {
            IncidentType.SOS -> Base(IncidentSeverity.CRITICAL, 0.95, "type:sos")
            IncidentType.HAZARD -> {
                val kind = event.payload["kind"]?.lowercase()
                when (kind) {
                    "fire" -> Base(IncidentSeverity.HIGH, 0.75, "hazard:fire")
                    "flood" -> Base(IncidentSeverity.HIGH, 0.75, "hazard:flood")
                    "bridge" -> Base(IncidentSeverity.HIGH, 0.8, "hazard:bridge_failure")
                    "closed_area" -> Base(IncidentSeverity.MEDIUM, 0.7, "hazard:closed_area")
                    else -> Base(IncidentSeverity.MEDIUM, 0.6, "hazard:unspecified")
                }
            }
            IncidentType.ROUTE_BLOCK -> Base(IncidentSeverity.MEDIUM, 0.7, "type:route_block")
            IncidentType.SHELTER_STATUS -> Base(IncidentSeverity.LOW, 0.6, "type:shelter_status")
            IncidentType.ALERT -> Base(IncidentSeverity.HIGH, 0.8, "type:alert")
            IncidentType.ACK -> Base(IncidentSeverity.LOW, 0.5, "type:ack")
        }

        val corroborated = independentReports > 0
        val confidence = if (corroborated) {
            (base.confidence + 0.1 * independentReports).coerceAtMost(1.0)
        } else {
            base.confidence
        }
        val reasons = buildList {
            add(base.reasonCode)
            if (corroborated) add("corroborated:by_$independentReports")
        }
        return TriageResult(base.severity, confidence, reasons)
    }

    private data class Base(val severity: IncidentSeverity, val confidence: Double, val reasonCode: String)
}

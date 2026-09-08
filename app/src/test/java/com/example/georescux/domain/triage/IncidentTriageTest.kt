package com.example.georescux.domain.triage

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentSeverity
import com.example.georescux.domain.incident.IncidentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the deterministic triage ruleset: severity, confidence, reason codes, corroboration. */
class IncidentTriageTest {

    private fun event(type: IncidentType, payload: Map<String, String> = emptyMap()) = IncidentEvent(
        eventId = "e", originId = "o", type = type, occurredAtMs = 1_000L, payload = payload,
    )

    @Test
    fun `sos incidents are critical with the type reason code`() {
        val result = IncidentTriage.triage(event(IncidentType.SOS))
        assertEquals(IncidentSeverity.CRITICAL, result.severity)
        assertEquals(listOf("type:sos"), result.reasonCodes)
        assertTrue(result.confidence > 0.9)
    }

    @Test
    fun `hazard kinds map to distinct severities and reason codes`() {
        val fire = IncidentTriage.triage(event(IncidentType.HAZARD, mapOf("kind" to "fire")))
        val flood = IncidentTriage.triage(event(IncidentType.HAZARD, mapOf("kind" to "flood")))
        val unspecified = IncidentTriage.triage(event(IncidentType.HAZARD))

        assertEquals(IncidentSeverity.HIGH, fire.severity)
        assertEquals(listOf("hazard:fire"), fire.reasonCodes)
        assertEquals(IncidentSeverity.HIGH, flood.severity)
        assertEquals(listOf("hazard:flood"), flood.reasonCodes)
        assertEquals(IncidentSeverity.MEDIUM, unspecified.severity)
        assertEquals(listOf("hazard:unspecified"), unspecified.reasonCodes)
    }

    @Test
    fun `route block is medium and shelter status is low`() {
        assertEquals(IncidentSeverity.MEDIUM, IncidentTriage.triage(event(IncidentType.ROUTE_BLOCK)).severity)
        assertEquals(IncidentSeverity.LOW, IncidentTriage.triage(event(IncidentType.SHELTER_STATUS)).severity)
    }

    @Test
    fun `corroboration raises confidence and adds an explicit reason code`() {
        val single = IncidentTriage.triage(event(IncidentType.SOS))
        val corroborated = IncidentTriage.triage(event(IncidentType.SOS), independentReports = 2)

        assertTrue(corroborated.confidence > single.confidence)
        assertTrue(corroborated.confidence <= 1.0)
        assertEquals(listOf("type:sos", "corroborated:by_2"), corroborated.reasonCodes)
    }

    @Test
    fun `confidence never exceeds one even with many reports`() {
        val result = IncidentTriage.triage(event(IncidentType.SOS), independentReports = 100)
        assertEquals(1.0, result.confidence, 0.0000001)
    }

    @Test
    fun `negative corroboration falls back safely`() {
        val result = IncidentTriage.triage(event(IncidentType.SOS), independentReports = -1)
        assertEquals(listOf("fallback:invalid-corroboration"), result.reasonCodes)
    }

    @Test
    fun `triage is deterministic`() {
        val event = event(IncidentType.HAZARD, mapOf("kind" to "flood"))
        assertEquals(IncidentTriage.triage(event), IncidentTriage.triage(event))
    }
}

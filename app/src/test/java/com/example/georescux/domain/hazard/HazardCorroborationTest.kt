package com.example.georescux.domain.hazard

import com.example.georescux.domain.incident.IncidentEvent
import com.example.georescux.domain.incident.IncidentSeverity
import com.example.georescux.domain.incident.IncidentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardCorroborationTest {

    private val corroboration = HazardCorroboration(
        minReportsRequired = 2,
        maxDistanceMeters = 200.0,
        timeWindowMs = 3_600_000L, // 1 hour
    )

    private val baseLat = 28.6298
    private val baseLng = 77.2245
    private val nowMs = 1_000_000_000_000L

    @Test
    fun `single unverified report is NOT corroborated`() {
        val singleReport = createHazardEvent(id = "e1", origin = "user1", lat = baseLat, lng = baseLng, time = nowMs)

        val result = corroboration.evaluateLocation(listOf(singleReport), baseLat, baseLng, nowMs)

        assertFalse("Single report must NOT be auto-trusted", result.isCorroborated)
        assertEquals(1, result.reportCount)
    }

    @Test
    fun `multiple reports near same location corroborate hazard`() {
        val report1 = createHazardEvent(id = "e1", origin = "user1", lat = baseLat, lng = baseLng, time = nowMs)
        val report2 = createHazardEvent(id = "e2", origin = "user2", lat = baseLat + 0.0005, lng = baseLng + 0.0005, time = nowMs - 60_000)

        val result = corroboration.evaluateLocation(listOf(report1, report2), baseLat, baseLng, nowMs)

        assertTrue("Multiple independent reports must corroborate hazard", result.isCorroborated)
        assertEquals(2, result.reportCount)
    }

    @Test
    fun `reports outside time window are ignored`() {
        val freshReport = createHazardEvent(id = "e1", origin = "user1", lat = baseLat, lng = baseLng, time = nowMs)
        val expiredReport = createHazardEvent(id = "e2", origin = "user2", lat = baseLat, lng = baseLng, time = nowMs - 4_000_000L) // >1hr ago

        val result = corroboration.evaluateLocation(listOf(freshReport, expiredReport), baseLat, baseLng, nowMs)

        assertFalse("Expired report must not contribute to corroboration", result.isCorroborated)
        assertEquals(1, result.reportCount)
    }

    @Test
    fun `duplicate reports from same user origin count only once`() {
        val report1 = createHazardEvent(id = "e1", origin = "user1", lat = baseLat, lng = baseLng, time = nowMs)
        val report2 = createHazardEvent(id = "e2", origin = "user1", lat = baseLat, lng = baseLng, time = nowMs - 100)

        val result = corroboration.evaluateLocation(listOf(report1, report2), baseLat, baseLng, nowMs)

        assertFalse("Duplicate reports from same origin must not bypass minimum report requirement", result.isCorroborated)
        assertEquals(1, result.reportCount)
    }

    @Test
    fun `findCorroboratedClusters groups hazards spatially`() {
        val report1 = createHazardEvent(id = "e1", origin = "user1", lat = baseLat, lng = baseLng, time = nowMs)
        val report2 = createHazardEvent(id = "e2", origin = "user2", lat = baseLat + 0.0001, lng = baseLng + 0.0001, time = nowMs)

        val distant1 = createHazardEvent(id = "e3", origin = "user3", lat = baseLat + 0.1, lng = baseLng + 0.1, time = nowMs)

        val clusters = corroboration.findCorroboratedClusters(listOf(report1, report2, distant1), nowMs)

        val corroborated = clusters.filter { it.isCorroborated }
        assertEquals(1, corroborated.size)
        assertEquals(2, corroborated.first().reportCount)
    }

    private fun createHazardEvent(
        id: String,
        origin: String,
        lat: Double,
        lng: Double,
        time: Long,
    ): IncidentEvent = IncidentEvent(
        eventId = id,
        originId = origin,
        type = IncidentType.HAZARD,
        occurredAtMs = time,
        latitude = lat,
        longitude = lng,
        severity = IncidentSeverity.HIGH,
    )
}

package com.example.georescux.domain.sos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the pure SOS cloud payload mapping: all fields preserved,
 * nullable values handled, ACTIVE/COMPLETED status derived from
 * stoppedAtMs, and deterministic output.
 */
class SosCloudPayloadTest {

    private fun location() = com.example.georescux.domain.sos.SosLocation(
        latitude = 52.5200,
        longitude = 13.4050,
        accuracyMeters = 12f,
        timestampMs = 5_000L,
        provider = "gps",
    )

    @Test
    fun `completed emergency with location maps every field`() {
        val emergency = SosEmergency(
            id = "alert-1",
            startedAtMs = 1_000L,
            stoppedAtMs = 9_000L,
            location = location(),
            locationStatus = SosLocationStatus.ACQUIRED,
        )

        assertEquals(
            mapOf(
                "id" to "alert-1",
                "status" to "COMPLETED",
                "startedAtMs" to 1_000L,
                "stoppedAtMs" to 9_000L,
                "location" to mapOf(
                    "latitude" to 52.5200,
                    "longitude" to 13.4050,
                    "accuracyMeters" to 12f,
                    "timestampMs" to 5_000L,
                    "provider" to "gps",
                ),
                "locationStatus" to "ACQUIRED",
            ),
            SosCloudPayload.fromEmergency(emergency)
        )
    }

    @Test
    fun `active emergency without location maps nullable fields to null`() {
        val emergency = SosEmergency(
            id = "alert-2",
            startedAtMs = 2_000L,
            stoppedAtMs = null,
            location = null,
            locationStatus = null,
        )

        assertEquals(
            mapOf(
                "id" to "alert-2",
                "status" to "ACTIVE",
                "startedAtMs" to 2_000L,
                "stoppedAtMs" to null,
                "location" to null,
                "locationStatus" to null,
            ),
            SosCloudPayload.fromEmergency(emergency)
        )
    }

    @Test
    fun `active emergency with unavailable location keeps the status`() {
        val emergency = SosEmergency(
            id = "alert-3",
            startedAtMs = 3_000L,
            location = null,
            locationStatus = SosLocationStatus.UNAVAILABLE,
        )

        val payload = SosCloudPayload.fromEmergency(emergency)
        assertEquals("ACTIVE", payload["status"])
        assertEquals("UNAVAILABLE", payload["locationStatus"])
        assertEquals(null, payload["location"])
    }

    @Test
    fun `history mapping is keyed by alert id`() {
        val alerts = listOf(
            SosEmergency(id = "alert-1", startedAtMs = 1_000L, stoppedAtMs = 2_000L),
            SosEmergency(id = "alert-2", startedAtMs = 3_000L),
        )

        val payload = SosCloudPayload.fromHistory(alerts)

        assertEquals(setOf("alert-1", "alert-2"), payload.keys)
        assertEquals("COMPLETED", payload["alert-1"]!!["status"])
        assertEquals("ACTIVE", payload["alert-2"]!!["status"])
    }

    @Test
    fun `mapping is deterministic`() {
        val emergency = SosEmergency("alert-1", startedAtMs = 1_000L, stoppedAtMs = 2_000L)
        assertEquals(SosCloudPayload.fromEmergency(emergency), SosCloudPayload.fromEmergency(emergency))
    }

    @Test
    fun `empty history maps to an empty payload`() {
        assertEquals(emptyMap<String, Map<String, Any?>>(), SosCloudPayload.fromHistory(emptyList()))
    }
}

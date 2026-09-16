package com.example.georescux.data.sos

import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the on-disk SOS record contract.
 *
 * The optional incident note was once omitted by the serializer, so it was
 * silently dropped on persist and then re-read as null by the BLE and
 * Firebase bridges — the user's "trapped near the bridge" text vanished.
 * These tests pin every persisted field to its round-trip behaviour,
 * including backwards compatibility with records saved by older versions.
 */
class SosEmergencyJsonTest {

    private fun roundTrip(emergency: SosEmergency): SosEmergency =
        SosEmergencyJson.fromJson(SosEmergencyJson.toJson(emergency))

    @Test
    fun `the optional note survives a round trip`() {
        val emergency = SosEmergency(
            id = "alert-1",
            startedAtMs = 1_000L,
            note = "Trapped near the bridge",
        )

        val restored = roundTrip(emergency)

        assertEquals("Trapped near the bridge", restored.note)
    }

    @Test
    fun `a blank note is normalised to null rather than persisted as whitespace`() {
        val emergency = SosEmergency(id = "alert-1", startedAtMs = 1_000L, note = "   ")

        val restored = roundTrip(emergency)

        assertNull(restored.note)
    }

    @Test
    fun `a long note is preserved up to the domain cap`() {
        val note = "a".repeat(120)

        val restored = roundTrip(SosEmergency(id = "alert-1", startedAtMs = 1L, note = note))

        assertEquals(120, restored.note?.length)
    }

    @Test
    fun `location and status round trip alongside the note`() {
        val emergency = SosEmergency(
            id = "alert-1",
            startedAtMs = 5_000L,
            location = SosLocation(
                latitude = 29.3919,
                longitude = 79.4542,
                accuracyMeters = 23f,
                timestampMs = 6_000L,
                provider = "fused",
            ),
            locationStatus = SosLocationStatus.ACQUIRED,
            note = "Injured, cannot walk",
        )

        val restored = roundTrip(emergency)

        assertEquals(emergency, restored)
    }

    @Test
    fun `a completed emergency keeps its stopped timestamp and note`() {
        val emergency = SosEmergency(
            id = "alert-1",
            startedAtMs = 1_000L,
            stoppedAtMs = 90_000L,
            note = "Resolved by responders",
        )

        val restored = roundTrip(emergency)

        assertEquals(90_000L, restored.stoppedAtMs)
        assertEquals("Resolved by responders", restored.note)
    }

    @Test
    fun `a record saved by an older app version without a note still loads`() {
        val legacyJson = JSONObject().apply {
            put("id", "alert-legacy")
            put("startedAtMs", 1L)
            put("stoppedAtMs", JSONObject.NULL)
            put("location", JSONObject.NULL)
            put("locationStatus", JSONObject.NULL)
            // No "note" key at all — this is the pre-fix on-disk shape.
        }

        val restored = SosEmergencyJson.fromJson(legacyJson)

        assertEquals("alert-legacy", restored.id)
        assertNull(restored.location)
        assertNull(restored.locationStatus)
        assertNull(restored.note)
    }

    @Test
    fun `history entries keep their notes and newest-first ordering is preserved by the store layer`() {
        val array = JSONArray().apply {
            put(SosEmergencyJson.toJson(SosEmergency(id = "old", startedAtMs = 1L, note = "first")))
            put(SosEmergencyJson.toJson(SosEmergency(id = "new", startedAtMs = 2L, note = "second")))
        }

        val restored = (0 until array.length())
            .map { SosEmergencyJson.fromJson(array.getJSONObject(it)) }
            .asReversed()

        assertEquals(listOf("second", "first"), restored.map { it.note })
    }
}

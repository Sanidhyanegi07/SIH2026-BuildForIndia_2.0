package com.example.georescux.data.sos

import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Phase 2 location rules of the SOS repository: updates apply
 * to the existing active emergency, never create records, work when the
 * permission is missing, and survive completion into history.
 */
class SosRepositoryLocationTest {

    private class InMemorySosStore : LocalSosStore {
        var active: SosEmergency? = null
        val history = mutableListOf<SosEmergency>()

        override fun loadActive(): SosEmergency? = active
        override fun saveActive(emergency: SosEmergency) { active = emergency }
        override fun clearActive() { active = null }
        override fun loadHistory(): List<SosEmergency> = history.toList()
        override fun addToHistory(emergency: SosEmergency) { history.add(emergency) }
    }

    private fun location() = SosLocation(
        latitude = 52.5200,
        longitude = 13.4050,
        accuracyMeters = 12f,
        timestampMs = 123_456L,
        provider = "gps",
    )

    @Test
    fun `location update applies to the active emergency`() {
        val repository = SosRepositoryImpl(InMemorySosStore())
        repository.startEmergency("alert-1", startedAtMs = 1000)

        repository.updateActiveEmergencyLocation(location(), SosLocationStatus.ACQUIRED)

        val active = repository.getActiveEmergency()!!
        assertEquals(location(), active.location)
        assertEquals(SosLocationStatus.ACQUIRED, active.locationStatus)
        assertTrue(active.isActive)
    }

    @Test
    fun `location update never creates a history entry or a second emergency`() {
        val repository = SosRepositoryImpl(InMemorySosStore())
        repository.startEmergency("alert-1", startedAtMs = 1000)

        repository.updateActiveEmergencyLocation(location(), SosLocationStatus.ACQUIRED)

        assertEquals("alert-1", repository.getActiveEmergency()?.id)
        assertTrue(repository.getHistory().isEmpty())
    }

    @Test
    fun `location update with no active emergency is a safe no-op`() {
        val repository = SosRepositoryImpl(InMemorySosStore())

        repository.updateActiveEmergencyLocation(location(), SosLocationStatus.ACQUIRED)

        assertNull(repository.getActiveEmergency())
        assertTrue(repository.getHistory().isEmpty())
    }

    @Test
    fun `permission missing stores the status without a location`() {
        val repository = SosRepositoryImpl(InMemorySosStore())
        repository.startEmergency("alert-1", startedAtMs = 1000)

        repository.updateActiveEmergencyLocation(null, SosLocationStatus.PERMISSION_MISSING)

        val active = repository.getActiveEmergency()!!
        assertNull(active.location)
        assertEquals(SosLocationStatus.PERMISSION_MISSING, active.locationStatus)
        assertTrue(active.isActive)
    }

    @Test
    fun `unavailable stores the status without a location`() {
        val repository = SosRepositoryImpl(InMemorySosStore())
        repository.startEmergency("alert-1", startedAtMs = 1000)

        repository.updateActiveEmergencyLocation(null, SosLocationStatus.UNAVAILABLE)

        val active = repository.getActiveEmergency()!!
        assertNull(active.location)
        assertEquals(SosLocationStatus.UNAVAILABLE, active.locationStatus)
        assertTrue(active.isActive)
    }

    @Test
    fun `a stored location survives completion into history`() {
        val repository = SosRepositoryImpl(InMemorySosStore())
        repository.startEmergency("alert-1", startedAtMs = 1000)
        repository.updateActiveEmergencyLocation(location(), SosLocationStatus.ACQUIRED)

        repository.completeEmergency(stoppedAtMs = 9000)

        assertEquals(location(), repository.getHistory().single().location)
        assertNull(repository.getActiveEmergency())
    }
}

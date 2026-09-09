package com.example.georescux.domain.sos

import com.example.georescux.data.sos.LocalSosStore
import com.example.georescux.data.sos.SosRepositoryImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies Phase 7 & Phase 13:
 * - SOS activation does not depend on network
 * - Location failure does not cancel SOS
 * - Emergency data persists locally
 */
class SosOfflineResilienceTest {

    private class InMemorySosStore : LocalSosStore {
        var active: SosEmergency? = null
        val history = mutableListOf<SosEmergency>()

        override fun loadActive(): SosEmergency? = active
        override fun saveActive(emergency: SosEmergency) { active = emergency }
        override fun clearActive() { active = null }
        override fun loadHistory(): List<SosEmergency> = history.toList()
        override fun addToHistory(emergency: SosEmergency) { history.add(0, emergency) }
    }

    @Test
    fun `SOS activates immediately without network connection`() {
        val store = InMemorySosStore()
        val repo = SosRepositoryImpl(store)
        val startUseCase = StartSosUseCase(repo)

        val emergency = startUseCase(id = "sos_test_1", startedAtMs = 1_000_000L)
        assertNotNull("SOS must activate without network", emergency)
        assertTrue("Activated SOS must be active", emergency!!.isActive)
        assertEquals(1_000_000L, emergency.startedAtMs)

        // Verifies local persistence
        val stored = repo.getActiveEmergency()
        assertNotNull("Emergency must be stored in local persistence", stored)
        assertEquals(emergency.id, stored?.id)
    }

    @Test
    fun `location failure does not cancel or invalidate SOS`() {
        val store = InMemorySosStore()
        val repo = SosRepositoryImpl(store)
        val startUseCase = StartSosUseCase(repo)
        val updateLocationUseCase = UpdateSosLocationUseCase(repo)

        val emergency = startUseCase(id = "sos_test_2", startedAtMs = 1_000_000L)
        assertNotNull(emergency)

        // Scenario: GPS fails or permission denied
        updateLocationUseCase(status = SosLocationStatus.PERMISSION_MISSING, location = null)

        val updated = repo.getActiveEmergency()
        assertNotNull("Emergency must remain active despite location failure", updated)
        assertTrue(updated!!.isActive)
        assertEquals(SosLocationStatus.PERMISSION_MISSING, updated.locationStatus)
        assertNull(updated.location)

        // Scenario: GPS times out / unavailable
        updateLocationUseCase(status = SosLocationStatus.UNAVAILABLE, location = null)
        val updated2 = repo.getActiveEmergency()
        assertNotNull("Emergency must remain active despite GPS unavailable", updated2)
        assertTrue(updated2!!.isActive)
        assertEquals(SosLocationStatus.UNAVAILABLE, updated2.locationStatus)
    }

    @Test
    fun `best-effort location updates store location when fix arrives`() {
        val store = InMemorySosStore()
        val repo = SosRepositoryImpl(store)
        val startUseCase = StartSosUseCase(repo)
        val updateLocationUseCase = UpdateSosLocationUseCase(repo)

        startUseCase(id = "sos_test_3", startedAtMs = 1_000_000L)
        val fixLocation = SosLocation(
            latitude = 30.3165,
            longitude = 78.0322,
            accuracyMeters = 5.0f,
            timestampMs = 1_000_500L,
            provider = "gps"
        )
        updateLocationUseCase(status = SosLocationStatus.ACQUIRED, location = fixLocation)

        val updated = repo.getActiveEmergency()
        assertNotNull(updated)
        assertEquals(SosLocationStatus.ACQUIRED, updated?.locationStatus)
        assertEquals(30.3165, updated?.location?.latitude ?: 0.0, 0.0001)
        assertEquals(78.0322, updated?.location?.longitude ?: 0.0, 0.0001)
    }

    @Test
    fun `stopping SOS moves emergency to local history`() {
        val store = InMemorySosStore()
        val repo = SosRepositoryImpl(store)
        val startUseCase = StartSosUseCase(repo)
        val stopUseCase = StopSosUseCase(repo)

        startUseCase(id = "sos_test_4", startedAtMs = 1_000_000L)
        stopUseCase(stoppedAtMs = 1_060_000L)

        assertNull("Active emergency must be cleared", repo.getActiveEmergency())
        val history = repo.getHistory()
        assertEquals(1, history.size)
        assertEquals(1_060_000L, history[0].stoppedAtMs)
        assertTrue("Emergency is completed", !history[0].isActive)
    }
}

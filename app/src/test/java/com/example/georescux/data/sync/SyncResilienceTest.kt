package com.example.georescux.data.sync

import com.example.georescux.domain.repository.AuthRepository
import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies Phase 12 & Phase 19:
 * - Pending data remains locally stored
 * - WorkManager/connectivity retries synchronization
 * - Duplicate sync does not corrupt data
 * - Cloud errors are contained
 */
class SyncResilienceTest {

    private class FakeSyncStateStore : SyncStateStore {
        val pendingContacts = mutableSetOf<String>()
        val pendingAlerts = mutableMapOf<String, MutableSet<String>>()

        override fun isContactBackupPending(uid: String): Boolean = pendingContacts.contains(uid)
        override fun setContactBackupPending(uid: String, pending: Boolean) {
            if (pending) pendingContacts.add(uid) else pendingContacts.remove(uid)
        }

        override fun pendingSosAlertIds(uid: String): Set<String> = pendingAlerts[uid].orEmpty()
        override fun setSosAlertPending(uid: String, alertId: String, pending: Boolean) {
            val set = pendingAlerts.getOrPut(uid) { mutableSetOf() }
            if (pending) set.add(alertId) else set.remove(alertId)
        }

        override fun clearSosAlertPending(uid: String, alertId: String) {
            pendingAlerts[uid]?.remove(alertId)
        }
    }

    private class FakeAuthRepo(override val currentUserId: String? = "test_user_123") : AuthRepository {
        override val isSignedIn: Boolean = currentUserId != null
        override val currentUserEmail: String? = "test@example.com"
        override suspend fun signIn(email: String, password: String) = throw UnsupportedOperationException()
        override suspend fun signUp(email: String, password: String) = throw UnsupportedOperationException()
        override fun signOut() {}
    }

    private class FakeSosLocalRepo : SosRepository {
        var active: SosEmergency? = null
        val historyRecords = mutableListOf<SosEmergency>()

        override fun getActiveEmergency(): SosEmergency? = active

        override fun startEmergency(id: String, startedAtMs: Long): SosEmergency? {
            val emergency = SosEmergency(id = id, startedAtMs = startedAtMs)
            active = emergency
            return emergency
        }

        override fun completeEmergency(stoppedAtMs: Long): SosEmergency? {
            val current = active ?: return null
            val completed = current.copy(stoppedAtMs = stoppedAtMs)
            active = null
            historyRecords.add(0, completed)
            return completed
        }

        override fun updateActiveEmergencyLocation(location: SosLocation?, status: SosLocationStatus) {
            active = active?.copy(location = location, locationStatus = status)
        }

        override fun getHistory(): List<SosEmergency> = historyRecords.toList()
    }

    private class CountingCloudDataSource(var shouldSucceed: Boolean = true) : SosCloudDataSource {
        var upsertCount = 0
        var historyCount = 0
        val uploadedEmergencies = mutableListOf<SosEmergency>()

        override suspend fun upsertEmergency(emergency: SosEmergency): Boolean {
            upsertCount++
            if (shouldSucceed) {
                uploadedEmergencies.add(emergency)
                return true
            }
            return false
        }

        override suspend fun upsertHistory(emergencies: List<SosEmergency>): Boolean {
            historyCount++
            if (shouldSucceed) {
                uploadedEmergencies.addAll(emergencies)
                return true
            }
            return false
        }
    }

    @Test
    fun `offline save keeps pending marker and stores data locally`() = runBlocking {
        val syncStore = FakeSyncStateStore()
        val localRepo = FakeSosLocalRepo()
        val cloud = CountingCloudDataSource(shouldSucceed = false) // Simulate offline
        val auth = FakeAuthRepo()

        val syncingRepo = SyncingSosRepository(
            local = localRepo,
            authRepository = auth,
            cloudDataSource = cloud,
            syncStateStore = syncStore,
        )

        syncingRepo.startEmergency("sos_1", 1_000_000L)

        // Verifies local store holds the alert
        assertEquals("sos_1", localRepo.getActiveEmergency()?.id)
        // Verifies pending flag is set for alert
        assertTrue(
            "Alert must be marked pending sync",
            syncStore.pendingSosAlertIds("test_user_123").contains("sos_1")
        )
    }

    @Test
    fun `connectivity restoration clears pending marker upon successful sync`() = runBlocking {
        val syncStore = FakeSyncStateStore()
        val localRepo = FakeSosLocalRepo()
        val cloud = CountingCloudDataSource(shouldSucceed = false)
        val auth = FakeAuthRepo()

        val syncingRepo = SyncingSosRepository(
            local = localRepo,
            authRepository = auth,
            cloudDataSource = cloud,
            syncStateStore = syncStore,
        )

        syncingRepo.startEmergency("sos_1", 1_000_000L)
        assertTrue(syncStore.pendingSosAlertIds("test_user_123").contains("sos_1"))

        // Connection restored:
        cloud.shouldSucceed = true
        syncingRepo.retryPendingBackups()

        // Verifies pending flag is cleared
        assertFalse(
            "Pending flag must be cleared after successful sync",
            syncStore.pendingSosAlertIds("test_user_123").contains("sos_1")
        )
        assertEquals(1, cloud.uploadedEmergencies.size)
        assertEquals("sos_1", cloud.uploadedEmergencies[0].id)
    }

    @Test
    fun `duplicate sync executions are idempotent and do not duplicate local data`() = runBlocking {
        val syncStore = FakeSyncStateStore()
        val localRepo = FakeSosLocalRepo()
        val cloud = CountingCloudDataSource(shouldSucceed = true)
        val auth = FakeAuthRepo()

        val syncingRepo = SyncingSosRepository(
            local = localRepo,
            authRepository = auth,
            cloudDataSource = cloud,
            syncStateStore = syncStore,
        )

        syncingRepo.startEmergency("sos_1", 1_000_000L)
        syncingRepo.completeEmergency(1_010_000L)

        // Multiple consecutive sync retries
        syncingRepo.retryPendingBackups()
        syncingRepo.retryPendingBackups()

        // Local history must contain exactly 1 entry
        assertEquals(1, localRepo.getHistory().size)
        assertEquals("sos_1", localRepo.getHistory()[0].id)
        assertTrue(syncStore.pendingSosAlertIds("test_user_123").isEmpty())
    }
}

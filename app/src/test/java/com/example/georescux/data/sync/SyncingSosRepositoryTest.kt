package com.example.georescux.data.sync

import com.example.georescux.domain.repository.AuthRepository
import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus
import com.example.georescux.core.result.AuthResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Stage 6 backup decorator: local SOS operations succeed first
 * and are mirrored best-effort to the cloud data source. Firebase failures
 * never fail a local SOS operation, signed-out users skip uploads, and the
 * app-start catch-up never modifies local data.
 *
 * Stage 7B-3 additionally verifies the durable per-alert pending state:
 * failed uploads mark the alert pending, successful ones clear it, and
 * [SyncingSosRepository.retryPendingBackups] re-pushes the CURRENT local
 * record for every pending alert id.
 */
class SyncingSosRepositoryTest {

    private class FakeLocalSosRepository : SosRepository {
        var active: SosEmergency? = null
        val historyRecords = mutableListOf<SosEmergency>()
        var locationUpdateCount = 0

        override fun getActiveEmergency(): SosEmergency? = active

        override fun startEmergency(id: String, startedAtMs: Long): SosEmergency? {
            if (active != null) return null
            val emergency = SosEmergency(id = id, startedAtMs = startedAtMs)
            active = emergency
            return emergency
        }

        override fun completeEmergency(stoppedAtMs: Long): SosEmergency? {
            val activeEmergency = active ?: return null
            val completed = activeEmergency.copy(stoppedAtMs = stoppedAtMs)
            active = null
            historyRecords.add(completed)
            return completed
        }

        override fun updateActiveEmergencyLocation(location: SosLocation?, status: SosLocationStatus) {
            locationUpdateCount++
            active = active?.copy(location = location, locationStatus = status)
        }

        override fun getHistory(): List<SosEmergency> = historyRecords.toList()
    }

    private class FakeAuthRepository(startUid: String?) : AuthRepository {
        var uid: String? = startUid
        override val isSignedIn: Boolean get() = uid != null
        override val currentUserEmail: String? get() = uid?.let { "$it@example.com" }
        override val currentUserId: String? get() = uid
        override suspend fun signIn(email: String, password: String): AuthResult =
            AuthResult.Error("not used in this test")

        override suspend fun signUp(email: String, password: String): AuthResult =
            AuthResult.Error("not used in this test")

        override fun signOut() {
            uid = null
        }
    }

    private class FakeSosCloudDataSource : SosCloudDataSource {
        var upsertCount = 0
        val upserted = mutableListOf<SosEmergency>()
        val upsertedById = mutableMapOf<String, SosEmergency>()
        var historyUploadCount = 0
        var lastHistoryUpload: List<SosEmergency>? = null
        var failUpserts = false
        var failHistory = false
        /** Alerts whose upsert fails even when [failUpserts] is false. */
        val failIds = mutableSetOf<String>()
        /** When set, upserts throw instead of returning — for cancellation tests. */
        var throwOnUpsert: Throwable? = null

        override suspend fun upsertEmergency(emergency: SosEmergency): Boolean {
            upsertCount++
            throwOnUpsert?.let { throw it }
            if (failUpserts || emergency.id in failIds) return false
            upserted.add(emergency)
            upsertedById[emergency.id] = emergency
            return true
        }

        override suspend fun upsertHistory(emergencies: List<SosEmergency>): Boolean {
            historyUploadCount++
            throwOnUpsert?.let { throw it }
            if (failHistory) return false
            lastHistoryUpload = emergencies
            emergencies.forEach { upsertedById[it.id] = it }
            return true
        }
    }

    private fun location() = SosLocation(
        latitude = 52.5200,
        longitude = 13.4050,
        accuracyMeters = 12f,
        timestampMs = 5_000L,
        provider = "gps",
    )

    private data class Harness(
        val repo: SyncingSosRepository,
        val local: FakeLocalSosRepository,
        val cloud: FakeSosCloudDataSource,
        val syncStateStore: SyncStateStoreFake,
    )

    /** Fresh decorator with an empty local repository (nothing started yet). */
    private fun repository(
        uid: String? = "user-a",
        cloud: FakeSosCloudDataSource = FakeSosCloudDataSource(),
        local: FakeLocalSosRepository = FakeLocalSosRepository(),
        syncStateStore: SyncStateStoreFake = SyncStateStoreFake(),
    ): Harness = Harness(
        repo = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository(uid),
            cloudDataSource = cloud,
            syncStateStore = syncStateStore,
            backupDispatcher = Dispatchers.Unconfined,
        ),
        local = local,
        cloud = cloud,
        syncStateStore = syncStateStore,
    )

    /** Decorator over a local repository that already has alert-1 active. */
    private fun activeRepository(
        cloud: FakeSosCloudDataSource = FakeSosCloudDataSource(),
        syncStateStore: SyncStateStoreFake = SyncStateStoreFake(),
    ): Harness {
        val local = FakeLocalSosRepository()
        local.startEmergency("alert-1", startedAtMs = 1_000L)
        val repo = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository("user-a"),
            cloudDataSource = cloud,
            syncStateStore = syncStateStore,
            backupDispatcher = Dispatchers.Unconfined,
        )
        return Harness(repo, local, cloud, syncStateStore)
    }

    @Test
    fun `successful start uploads the active emergency`() {
        val (repo, local, cloud) = repository()

        val result = repo.startEmergency("alert-1", startedAtMs = 1_000L)

        assertEquals("alert-1", result?.id)
        assertEquals("alert-1", local.getActiveEmergency()?.id)
        assertEquals(1, cloud.upsertCount)
        assertEquals(SosEmergency("alert-1", startedAtMs = 1_000L), cloud.upserted.single())
    }

    @Test
    fun `successful location update uploads the current active emergency`() {
        val (repository, local, _) = activeRepository()
        val cloud = FakeSosCloudDataSource()
        val repo = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository("user-a"),
            cloudDataSource = cloud,
            syncStateStore = SyncStateStoreFake(),
            backupDispatcher = Dispatchers.Unconfined,
        )

        repo.updateActiveEmergencyLocation(location(), SosLocationStatus.ACQUIRED)

        assertEquals(1, cloud.upsertCount)
        val uploaded = cloud.upserted.single()
        assertEquals(location(), uploaded.location)
        assertEquals(SosLocationStatus.ACQUIRED, uploaded.locationStatus)
        assertTrue(uploaded.isActive)
        assertEquals(1, local.locationUpdateCount)
    }

    @Test
    fun `successful completion uploads the completed emergency`() {
        val (repository, local, _) = activeRepository()
        val cloud = FakeSosCloudDataSource()
        val repo = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository("user-a"),
            cloudDataSource = cloud,
            syncStateStore = SyncStateStoreFake(),
            backupDispatcher = Dispatchers.Unconfined,
        )

        val result = repo.completeEmergency(stoppedAtMs = 9_000L)

        assertEquals(1, cloud.upsertCount)
        val uploaded = cloud.upserted.single()
        assertEquals("alert-1", uploaded.id)
        assertEquals(9_000L, uploaded.stoppedAtMs)
        assertTrue(!uploaded.isActive)
        assertEquals("alert-1", result?.id)
    }

    @Test
    fun `firebase failure during start does not fail the local start`() {
        val local = FakeLocalSosRepository()
        val cloud = FakeSosCloudDataSource().apply { failUpserts = true }
        val repository = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository("user-a"),
            cloudDataSource = cloud,
            syncStateStore = SyncStateStoreFake(),
            backupDispatcher = Dispatchers.Unconfined,
        )

        val result = repository.startEmergency("alert-1", startedAtMs = 1_000L)

        assertEquals("alert-1", result?.id)
        assertEquals("alert-1", local.getActiveEmergency()?.id)
    }

    @Test
    fun `firebase failure during location update does not fail the local update`() {
        val (repository, local, _) = activeRepository()
        val cloud = FakeSosCloudDataSource().apply { failUpserts = true }
        val repo = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository("user-a"),
            cloudDataSource = cloud,
            syncStateStore = SyncStateStoreFake(),
            backupDispatcher = Dispatchers.Unconfined,
        )

        repo.updateActiveEmergencyLocation(location(), SosLocationStatus.ACQUIRED)

        assertEquals(location(), local.getActiveEmergency()?.location)
        assertEquals(SosLocationStatus.ACQUIRED, local.getActiveEmergency()?.locationStatus)
    }

    @Test
    fun `firebase failure during completion does not fail the local completion`() {
        val (repository, local, _) = activeRepository()
        val cloud = FakeSosCloudDataSource().apply { failUpserts = true }
        val repo = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository("user-a"),
            cloudDataSource = cloud,
            syncStateStore = SyncStateStoreFake(),
            backupDispatcher = Dispatchers.Unconfined,
        )

        val result = repo.completeEmergency(stoppedAtMs = 9_000L)

        assertEquals("alert-1", result?.id)
        assertNull(local.getActiveEmergency())
        assertEquals(1, local.historyRecords.size)
    }

    @Test
    fun `signed-out users skip cloud uploads entirely`() {
        val local = FakeLocalSosRepository()
        val cloud = FakeSosCloudDataSource()
        val repository = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository(null),
            cloudDataSource = cloud,
            syncStateStore = SyncStateStoreFake(),
            backupDispatcher = Dispatchers.Unconfined,
        )

        repository.startEmergency("alert-1", startedAtMs = 1_000L)

        assertEquals(0, cloud.upsertCount)
    }

    @Test
    fun `same alert id upserts the same cloud node`() {
        val (repo, local, cloud) = repository()

        repo.startEmergency("alert-1", startedAtMs = 1_000L)
        repo.completeEmergency(stoppedAtMs = 9_000L)

        assertEquals(2, cloud.upsertCount)
        assertEquals(1, cloud.upsertedById.size) // same node, not a duplicate record
        assertEquals(9_000L, cloud.upsertedById["alert-1"]?.stoppedAtMs)
    }

    @Test
    fun `app-start backup uploads the active emergency and the history`() = kotlinx.coroutines.runBlocking {
        val local = FakeLocalSosRepository()
        local.startEmergency("alert-active", startedAtMs = 1_000L)
        val completed = local.completeEmergency(stoppedAtMs = 2_000L)!!
        local.startEmergency("alert-live", startedAtMs = 3_000L)
        val cloud = FakeSosCloudDataSource()
        val repository = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository("user-a"),
            cloudDataSource = cloud,
            syncStateStore = SyncStateStoreFake(),
            backupDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(repository.backupNow())

        assertEquals(1, cloud.historyUploadCount)
        assertEquals(listOf(completed), cloud.lastHistoryUpload)
        // The active record is re-added after the history mirror so it is not wiped.
        assertEquals("alert-live", cloud.upserted.single().id)
    }

    @Test
    fun `app-start backup does not modify local data`() = kotlinx.coroutines.runBlocking {
        val local = FakeLocalSosRepository()
        local.startEmergency("alert-1", startedAtMs = 1_000L)
        val cloud = FakeSosCloudDataSource()
        val repository = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository("user-a"),
            cloudDataSource = cloud,
            syncStateStore = SyncStateStoreFake(),
            backupDispatcher = Dispatchers.Unconfined,
        )
        val activeBefore = local.getActiveEmergency()
        val historyBefore = local.getHistory()

        assertTrue(repository.backupNow())

        assertEquals(activeBefore, local.getActiveEmergency())
        assertEquals(historyBefore, local.getHistory())
    }

    @Test
    fun `app-start backup with no active emergency and empty history is safe`() = kotlinx.coroutines.runBlocking {
        val local = FakeLocalSosRepository()
        val cloud = FakeSosCloudDataSource()
        val repository = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository("user-a"),
            cloudDataSource = cloud,
            syncStateStore = SyncStateStoreFake(),
            backupDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(repository.backupNow())

        assertEquals(0, cloud.upsertCount)
        assertEquals(1, cloud.historyUploadCount) // empty mirror is still pushed
        assertTrue(cloud.lastHistoryUpload!!.isEmpty())
    }

    @Test
    fun `signed-out app-start backup skips all uploads`() = kotlinx.coroutines.runBlocking {
        val local = FakeLocalSosRepository()
        local.startEmergency("alert-1", startedAtMs = 1_000L)
        val cloud = FakeSosCloudDataSource()
        val repository = SyncingSosRepository(
            local = local,
            authRepository = FakeAuthRepository(null),
            cloudDataSource = cloud,
            syncStateStore = SyncStateStoreFake(),
            backupDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(repository.backupNow())

        assertEquals(0, cloud.upsertCount)
        assertEquals(0, cloud.historyUploadCount)
    }

    // ---- Stage 7B-3: durable per-alert pending state + retry ----

    @Test
    fun `a successful backup clears the alert pending state`() {
        val (repo, _, _, syncStateStore) = repository()
        syncStateStore.setSosAlertPending("user-a", alertId = "alert-1", pending = true)

        repo.startEmergency("alert-1", startedAtMs = 1_000L)

        assertTrue(syncStateStore.pendingSosAlertIds("user-a").isEmpty())
    }

    @Test
    fun `a failed backup marks the alert pending`() {
        val (repo, _, _, syncStateStore) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })

        repo.startEmergency("alert-1", startedAtMs = 1_000L)

        assertEquals(setOf("alert-1"), syncStateStore.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `a pending alert stays pending after another failed attempt`() {
        val (repo, _, _, syncStateStore) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })
        repo.startEmergency("alert-1", startedAtMs = 1_000L)

        repo.updateActiveEmergencyLocation(location(), SosLocationStatus.ACQUIRED)

        assertEquals(setOf("alert-1"), syncStateStore.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `retryPendingBackups with nothing pending performs no uploads`() {
        val (repo, _, cloud, _) = repository()

        assertTrue(repo.retryPendingBackups())

        assertEquals(0, cloud.upsertCount)
        assertEquals(0, cloud.historyUploadCount)
    }

    @Test
    fun `retryPendingBackups uploads the current local record, not a stale one`() {
        val (repo, _, cloud, syncStateStore) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })
        // While the cloud is unreachable the alert goes ACTIVE → located → COMPLETED.
        repo.startEmergency("alert-1", startedAtMs = 1_000L)
        repo.updateActiveEmergencyLocation(location(), SosLocationStatus.ACQUIRED)
        repo.completeEmergency(stoppedAtMs = 9_000L)
        assertTrue(cloud.upserted.none { it.id == "alert-1" }) // every attempt failed

        cloud.failUpserts = false // connectivity "returns" before the retry
        assertTrue(repo.retryPendingBackups())

        val retried = cloud.upserted.single()
        assertEquals("alert-1", retried.id)
        assertEquals(9_000L, retried.stoppedAtMs) // CURRENT state: completed…
        assertEquals(location(), retried.location) // …including the acquired fix
        assertTrue(syncStateStore.pendingSosAlertIds("user-a").isEmpty())
    }

    @Test
    fun `a successful retry clears the alert pending state`() {
        val (repo, _, cloud, syncStateStore) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })
        repo.startEmergency("alert-1", startedAtMs = 1_000L)
        assertEquals(setOf("alert-1"), syncStateStore.pendingSosAlertIds("user-a"))

        cloud.failUpserts = false
        assertTrue(repo.retryPendingBackups())

        assertTrue(syncStateStore.pendingSosAlertIds("user-a").isEmpty())
    }

    @Test
    fun `a failed retry keeps the alert pending`() {
        val (repo, _, _, syncStateStore) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })
        repo.startEmergency("alert-1", startedAtMs = 1_000L)

        assertTrue(repo.retryPendingBackups())

        assertEquals(setOf("alert-1"), syncStateStore.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `multiple pending alerts are retried independently`() {
        val (repo, _, cloud, syncStateStore) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })
        repo.startEmergency("alert-1", startedAtMs = 1_000L)
        repo.completeEmergency(stoppedAtMs = 2_000L)
        repo.startEmergency("alert-2", startedAtMs = 3_000L)
        assertEquals(setOf("alert-1", "alert-2"), syncStateStore.pendingSosAlertIds("user-a"))

        cloud.failUpserts = false
        assertTrue(repo.retryPendingBackups())

        assertEquals(2, cloud.upsertedById.size)
        assertEquals(2_000L, cloud.upsertedById["alert-1"]?.stoppedAtMs)
        assertNull(cloud.upsertedById["alert-2"]?.stoppedAtMs)
        assertTrue(syncStateStore.pendingSosAlertIds("user-a").isEmpty())
    }

    @Test
    fun `one failed retry does not clear another pending alert`() {
        val (repo, _, cloud, syncStateStore) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })
        repo.startEmergency("alert-1", startedAtMs = 1_000L)
        repo.completeEmergency(stoppedAtMs = 2_000L)
        repo.startEmergency("alert-2", startedAtMs = 3_000L)

        cloud.failUpserts = false
        cloud.failIds.add("alert-1")
        assertTrue(repo.retryPendingBackups())

        assertEquals(setOf("alert-1"), syncStateStore.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `local start and location update succeed even when Firebase fails`() {
        val (repo, local, _, syncStateStore) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })

        repo.startEmergency("alert-1", startedAtMs = 1_000L)
        repo.updateActiveEmergencyLocation(location(), SosLocationStatus.ACQUIRED)

        assertEquals(location(), local.getActiveEmergency()?.location)
        assertEquals(SosLocationStatus.ACQUIRED, local.getActiveEmergency()?.locationStatus)
        assertEquals(setOf("alert-1"), syncStateStore.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `local completion succeeds even when Firebase fails`() {
        val (repo, local, _, syncStateStore) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })
        repo.startEmergency("alert-1", startedAtMs = 1_000L)

        val result = repo.completeEmergency(stoppedAtMs = 9_000L)

        assertEquals("alert-1", result?.id)
        assertNull(local.getActiveEmergency())
        assertEquals(1, local.historyRecords.size)
        assertEquals(setOf("alert-1"), syncStateStore.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `signed-out retry performs no upload and does not mutate pending state`() {
        val (repo, _, cloud, syncStateStore) = repository(uid = null)
        syncStateStore.setSosAlertPending("user-a", alertId = "alert-1", pending = true)

        assertTrue(repo.retryPendingBackups())

        assertEquals(0, cloud.upsertCount)
        assertEquals(0, cloud.historyUploadCount)
        assertEquals(setOf("alert-1"), syncStateStore.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `a later successful app-start backup clears older pending markers`() = kotlinx.coroutines.runBlocking {
        val (repo, _, cloud, syncStateStore) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })
        repo.startEmergency("alert-1", startedAtMs = 1_000L)
        repo.completeEmergency(stoppedAtMs = 2_000L)
        repo.startEmergency("alert-2", startedAtMs = 3_000L)
        assertEquals(setOf("alert-1", "alert-2"), syncStateStore.pendingSosAlertIds("user-a"))

        cloud.failUpserts = false
        assertTrue(repo.backupNow())

        assertTrue(syncStateStore.pendingSosAlertIds("user-a").isEmpty())
    }

    @Test
    fun `repeated retry is idempotent`() {
        val (repo, _, cloud, _) = repository(cloud = FakeSosCloudDataSource().apply { failUpserts = true })
        repo.startEmergency("alert-1", startedAtMs = 1_000L)
        cloud.failUpserts = false

        assertTrue(repo.retryPendingBackups())
        val upsertsAfterFirstRetry = cloud.upsertCount
        assertTrue(repo.retryPendingBackups())

        assertEquals(upsertsAfterFirstRetry, cloud.upsertCount) // nothing pending: no second push
    }

    @Test
    fun `cancellation is not swallowed by the backup path`() = kotlinx.coroutines.runBlocking {
        val (repo, local, cloud, _) = repository(cloud = FakeSosCloudDataSource().apply {
            throwOnUpsert = CancellationException("cancelled")
        })
        local.startEmergency("alert-1", startedAtMs = 1_000L) // local write, bypassing the decorator

        var thrown: CancellationException? = null
        try {
            repo.backupNow()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertEquals("cancelled", thrown?.message)
    }
}

package com.example.georescux.data.sync

import com.example.georescux.core.result.AuthResult
import com.example.georescux.domain.contacts.ContactPriority
import com.example.georescux.domain.contacts.EmergencyContact
import com.example.georescux.domain.repository.AuthRepository
import com.example.georescux.domain.repository.ContactsRepository
import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the Stage 7B-3 Step 4 retry triggers: [SyncRetryCoordinator]
 * fires the EXISTING pending-backup retry methods on app start
 * (retryPendingNow) and on connectivity regained (via the
 * [ConnectivityMonitor] registered by start/stop), coalescing triggers so
 * overlapping events never stack up uncontrolled retry jobs. The
 * coordinator never touches SyncStateStore itself — pending state stays
 * the durable mechanism, and each retry method decides per current UID
 * whether anything is pending.
 */
class SyncRetryCoordinatorTest {

    // ---- Fakes (mirroring the per-file fakes used by the other sync tests) ----

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

    private class FakeLocalContactsRepository : ContactsRepository {
        val localContacts = mutableListOf<EmergencyContact>()

        override fun getContacts(): List<EmergencyContact> = localContacts.toList()

        override fun saveContact(contact: EmergencyContact): List<EmergencyContact> {
            localContacts.removeAll { it.id == contact.id }
            localContacts.add(contact)
            return getContacts()
        }

        override fun deleteContact(id: String): List<EmergencyContact> {
            localContacts.removeAll { it.id == id }
            return getContacts()
        }
    }

    private class FakeContactsCloudDataSource : ContactsCloudDataSource {
        var uploadCount = 0
        var lastUpload: List<EmergencyContact>? = null
        var failUploads = false

        override suspend fun uploadContacts(contacts: List<EmergencyContact>): Boolean {
            uploadCount++
            if (failUploads) return false
            lastUpload = contacts
            return true
        }
    }

    private class FakeLocalSosRepository : SosRepository {
        var active: SosEmergency? = null
        val historyRecords = mutableListOf<SosEmergency>()

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
            active = active?.copy(location = location, locationStatus = status)
        }

        override fun getHistory(): List<SosEmergency> = historyRecords.toList()
    }

    private class FakeSosCloudDataSource : SosCloudDataSource {
        var upsertCount = 0
        val upserted = mutableListOf<SosEmergency>()
        var historyUploadCount = 0
        var failUpserts = false

        override suspend fun upsertEmergency(emergency: SosEmergency): Boolean {
            upsertCount++
            if (failUpserts) return false
            upserted.add(emergency)
            return true
        }

        override suspend fun upsertHistory(emergencies: List<SosEmergency>): Boolean {
            historyUploadCount++
            if (failUpserts) return false
            return true
        }
    }

    private class FakeConnectivityMonitor : ConnectivityMonitor {
        var startCount = 0
            private set
        var stopCount = 0
            private set
        private var listener: (() -> Unit)? = null
        val hasListener: Boolean get() = listener != null

        override fun start(onRegained: () -> Unit) {
            startCount++
            listener = onRegained
        }

        override fun stop() {
            stopCount++
            listener = null
        }

        fun simulateConnectivityRegained() {
            listener?.invoke()
        }
    }

    /** Stage 8: records ensureQueued() calls (no WorkManager on the JVM). */
    private class FakeSyncWorkScheduler : SyncWorkScheduler {
        val ensureCount = AtomicInteger(0)
        override fun ensureQueued() {
            ensureCount.incrementAndGet()
        }
    }

    /** Interface-level fakes for trigger-count assertions. */
    private class CountingContactsBackup : ContactsBackup {
        val retryCount = AtomicInteger(0)
        override suspend fun backupNow(): Boolean = true
        override fun retryPendingBackup(): Boolean {
            retryCount.incrementAndGet()
            return true
        }
    }

    private class CountingSosBackup : SosBackup {
        val retryCount = AtomicInteger(0)
        override suspend fun backupNow(): Boolean = true
        override fun retryPendingBackups(): Boolean {
            retryCount.incrementAndGet()
            return true
        }
    }

    private class ThrowingContactsBackup : ContactsBackup {
        override suspend fun backupNow(): Boolean = true
        override fun retryPendingBackup(): Boolean = throw IllegalStateException("contacts retry boom")
    }

    /** Retries block on a gate, tracking concurrency, to model a long retry run. */
    private class GatedContactsBackup : ContactsBackup {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val retryCount = AtomicInteger(0)
        private val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        override suspend fun backupNow(): Boolean = true

        override fun retryPendingBackup(): Boolean {
            retryCount.incrementAndGet()
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            entered.countDown()
            gate.await(5, TimeUnit.SECONDS)
            active.decrementAndGet()
            return true
        }
    }

    private class GatedSosBackup : SosBackup {
        val retryCount = AtomicInteger(0)
        private val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        override suspend fun backupNow(): Boolean = true

        override fun retryPendingBackups(): Boolean {
            retryCount.incrementAndGet()
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            active.decrementAndGet()
            return true
        }
    }

    // ---- Harness with the REAL sync decorators over fakes ----

    private data class Harness(
        val coordinator: SyncRetryCoordinator,
        val monitor: FakeConnectivityMonitor,
        val store: SyncStateStoreFake,
        val contactsRepo: SyncingContactsRepository,
        val sosRepo: SyncingSosRepository,
        val contactsLocal: FakeLocalContactsRepository,
        val sosLocal: FakeLocalSosRepository,
        val contactsCloud: FakeContactsCloudDataSource,
        val sosCloud: FakeSosCloudDataSource,
    )

    private fun harness(
        uid: String? = "user-a",
        failContactsUploads: Boolean = false,
        failSosUploads: Boolean = false,
    ): Harness {
        val store = SyncStateStoreFake()
        val contactsCloud = FakeContactsCloudDataSource().apply { failUploads = failContactsUploads }
        val sosCloud = FakeSosCloudDataSource().apply { failUpserts = failSosUploads }
        val contactsLocal = FakeLocalContactsRepository()
        val sosLocal = FakeLocalSosRepository()
        val contactsRepo = SyncingContactsRepository(
            local = contactsLocal,
            authRepository = FakeAuthRepository(uid),
            cloudDataSource = contactsCloud,
            syncStateStore = store,
            backupDispatcher = Dispatchers.Unconfined,
        )
        val sosRepo = SyncingSosRepository(
            local = sosLocal,
            authRepository = FakeAuthRepository(uid),
            cloudDataSource = sosCloud,
            syncStateStore = store,
            backupDispatcher = Dispatchers.Unconfined,
        )
        val monitor = FakeConnectivityMonitor()
        val coordinator = SyncRetryCoordinator(contactsRepo, sosRepo, monitor, Dispatchers.Unconfined)
        return Harness(
            coordinator, monitor, store, contactsRepo, sosRepo,
            contactsLocal, sosLocal, contactsCloud, sosCloud,
        )
    }

    private fun contact(id: String, priority: ContactPriority) = EmergencyContact(
        id = id,
        name = "Name $id",
        phoneNumber = "9876543210",
        relationship = "Friend",
        priority = priority,
    )

    // ---- A. Startup (the startup trigger is retryPendingNow) ----

    @Test
    fun `startup retry with nothing pending performs no cloud uploads`() {
        val h = harness()

        h.coordinator.retryPendingNow()

        assertEquals(0, h.contactsCloud.uploadCount)
        assertEquals(0, h.sosCloud.upsertCount)
        assertEquals(0, h.sosCloud.historyUploadCount)
    }

    @Test
    fun `startup retry pushes pending contacts`() {
        val h = harness(failContactsUploads = true)
        h.contactsRepo.saveContact(contact("c1", ContactPriority.SECONDARY)) // fails → pending
        assertTrue(h.store.isContactBackupPending("user-a"))
        h.contactsCloud.failUploads = false

        h.coordinator.retryPendingNow()

        assertEquals(listOf(contact("c1", ContactPriority.SECONDARY)), h.contactsCloud.lastUpload)
        assertFalse(h.store.isContactBackupPending("user-a"))
        assertEquals(0, h.sosCloud.upsertCount)
    }

    @Test
    fun `startup retry pushes pending SOS alerts`() {
        val h = harness(failSosUploads = true)
        h.sosRepo.startEmergency("alert-1", startedAtMs = 1_000L) // fails → pending
        assertEquals(setOf("alert-1"), h.store.pendingSosAlertIds("user-a"))
        h.sosCloud.failUpserts = false

        h.coordinator.retryPendingNow()

        assertEquals(1, h.sosCloud.upserted.size)
        assertEquals("alert-1", h.sosCloud.upserted.single().id)
        assertTrue(h.store.pendingSosAlertIds("user-a").isEmpty())
        assertEquals(0, h.contactsCloud.uploadCount)
    }

    @Test
    fun `signed-out startup retry performs no cloud work and leaves pending state untouched`() {
        val h = harness(uid = null)
        h.store.setContactBackupPending("user-a", pending = true)
        h.store.setSosAlertPending("user-a", alertId = "alert-1", pending = true)

        h.coordinator.retryPendingNow()

        assertEquals(0, h.contactsCloud.uploadCount)
        assertEquals(0, h.sosCloud.upsertCount)
        assertEquals(0, h.sosCloud.historyUploadCount)
        assertTrue(h.store.isContactBackupPending("user-a"))
        assertEquals(setOf("alert-1"), h.store.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `a throwing contacts retry does not crash the trigger or block the sos retry`() {
        val contacts = ThrowingContactsBackup()
        val sos = CountingSosBackup()
        val coordinator = SyncRetryCoordinator(contacts, sos, FakeConnectivityMonitor(), Dispatchers.Unconfined)

        coordinator.retryPendingNow() // must not throw

        assertEquals(1, sos.retryCount.get()) // SOS still attempted after the contacts failure
    }

    // ---- B. Connectivity regained ----

    @Test
    fun `connectivity regained retries pending contacts`() {
        val h = harness(failContactsUploads = true)
        h.contactsRepo.saveContact(contact("c1", ContactPriority.SECONDARY)) // fails → pending
        h.contactsCloud.failUploads = false

        h.coordinator.start()
        h.monitor.simulateConnectivityRegained()

        assertEquals(listOf(contact("c1", ContactPriority.SECONDARY)), h.contactsCloud.lastUpload)
        assertFalse(h.store.isContactBackupPending("user-a"))
        assertEquals(0, h.sosCloud.upsertCount)
    }

    @Test
    fun `connectivity regained retries pending SOS alerts`() {
        val h = harness(failSosUploads = true)
        h.sosRepo.startEmergency("alert-1", startedAtMs = 1_000L) // fails → pending
        h.sosCloud.failUpserts = false

        h.coordinator.start()
        h.monitor.simulateConnectivityRegained()

        assertEquals(1, h.sosCloud.upserted.size)
        assertTrue(h.store.pendingSosAlertIds("user-a").isEmpty())
        assertEquals(0, h.contactsCloud.uploadCount)
    }

    @Test
    fun `start alone performs no cloud work while offline`() {
        val h = harness()
        h.store.setContactBackupPending("user-a", pending = true)
        h.sosLocal.startEmergency("alert-1", startedAtMs = 1_000L)
        h.store.setSosAlertPending("user-a", alertId = "alert-1", pending = true)

        h.coordinator.start() // registration only — no connectivity event is delivered

        assertEquals(0, h.contactsCloud.uploadCount)
        assertEquals(0, h.sosCloud.upsertCount)
        assertEquals(0, h.sosCloud.historyUploadCount)
    }

    @Test
    fun `repeated triggers while a retry runs never overlap and coalesce into one follow-up`() {
        val contacts = GatedContactsBackup()
        val sos = GatedSosBackup()
        val coordinator = SyncRetryCoordinator(contacts, sos, FakeConnectivityMonitor(), Dispatchers.Unconfined)

        // Run #1 starts and blocks inside the retry (modeling a long run).
        val worker = Thread { coordinator.retryPendingNow() }
        worker.start()
        assertTrue(contacts.entered.await(5, TimeUnit.SECONDS))

        // Two more connectivity triggers arrive while run #1 is in progress:
        // neither may start an overlapping job — they coalesce into ONE follow-up.
        coordinator.retryPendingNow()
        coordinator.retryPendingNow()

        contacts.gate.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(5))

        assertEquals(1, contacts.maxActive.get()) // never two runs at once
        assertEquals(1, sos.maxActive.get())
        assertEquals(2, contacts.retryCount.get()) // run #1 + one coalesced follow-up (not 3)
        assertEquals(2, sos.retryCount.get())
    }

    @Test
    fun `a failed connectivity retry keeps pending state for the next event`() {
        val h = harness(failSosUploads = true)
        h.sosRepo.startEmergency("alert-1", startedAtMs = 1_000L) // fails → pending
        h.coordinator.start()

        h.monitor.simulateConnectivityRegained() // cloud still failing
        assertEquals(setOf("alert-1"), h.store.pendingSosAlertIds("user-a"))
        assertEquals(0, h.sosCloud.upserted.size)

        h.sosCloud.failUpserts = false
        h.monitor.simulateConnectivityRegained() // a later genuine event
        assertTrue(h.store.pendingSosAlertIds("user-a").isEmpty())
        assertEquals(1, h.sosCloud.upserted.size)
    }

    @Test
    fun `retry never touches another user's pending data`() {
        val h = harness(uid = "user-b")
        h.store.setContactBackupPending("user-a", pending = true)
        h.store.setSosAlertPending("user-a", alertId = "alert-1", pending = true)

        h.coordinator.start()
        h.monitor.simulateConnectivityRegained() // user-b's session — nothing pending for it

        assertEquals(0, h.contactsCloud.uploadCount)
        assertEquals(0, h.sosCloud.upsertCount)
        assertTrue(h.store.isContactBackupPending("user-a"))
        assertEquals(setOf("alert-1"), h.store.pendingSosAlertIds("user-a"))
    }

    // ---- C. Lifecycle ----

    @Test
    fun `start registers exactly one connectivity trigger`() {
        val contacts = CountingContactsBackup()
        val sos = CountingSosBackup()
        val monitor = FakeConnectivityMonitor()
        val coordinator = SyncRetryCoordinator(contacts, sos, monitor, Dispatchers.Unconfined)

        coordinator.start()

        assertEquals(1, monitor.startCount)
        assertTrue(monitor.hasListener)
    }

    @Test
    fun `repeated start does not duplicate the connectivity trigger`() {
        val contacts = CountingContactsBackup()
        val sos = CountingSosBackup()
        val monitor = FakeConnectivityMonitor()
        val coordinator = SyncRetryCoordinator(contacts, sos, monitor, Dispatchers.Unconfined)

        coordinator.start()
        coordinator.start()
        coordinator.start()

        assertEquals(1, monitor.startCount)
    }

    @Test
    fun `stop unregisters the trigger so later events do nothing and start works again`() {
        val contacts = CountingContactsBackup()
        val sos = CountingSosBackup()
        val monitor = FakeConnectivityMonitor()
        val coordinator = SyncRetryCoordinator(contacts, sos, monitor, Dispatchers.Unconfined)

        coordinator.start()
        coordinator.stop()

        assertEquals(1, monitor.stopCount)
        assertFalse(monitor.hasListener)
        monitor.simulateConnectivityRegained() // no listener: must do nothing
        assertEquals(0, contacts.retryCount.get())
        assertEquals(0, sos.retryCount.get())

        coordinator.start() // re-registration works
        assertEquals(2, monitor.startCount)
        monitor.simulateConnectivityRegained()
        assertEquals(1, contacts.retryCount.get())
        assertEquals(1, sos.retryCount.get())
    }

    // ---- Stage 8: WorkManager-scheduled trigger (docs/STAGE8_SYNC_RETRY_DESIGN.md) ----

    @Test
    fun `start ensures the pending-sync work is queued exactly once`() {
        val contacts = CountingContactsBackup()
        val sos = CountingSosBackup()
        val scheduler = FakeSyncWorkScheduler()
        val coordinator = SyncRetryCoordinator(contacts, sos, FakeConnectivityMonitor(), Dispatchers.Unconfined, scheduler)

        coordinator.start()
        coordinator.start() // idempotent — never a second registration
        coordinator.start()

        assertEquals(1, scheduler.ensureCount.get())
    }

    @Test
    fun `every retryPendingNow trigger ensures the sync work is queued`() {
        val contacts = CountingContactsBackup()
        val sos = CountingSosBackup()
        val scheduler = FakeSyncWorkScheduler()
        val coordinator = SyncRetryCoordinator(contacts, sos, FakeConnectivityMonitor(), Dispatchers.Unconfined, scheduler)

        coordinator.retryPendingNow()
        coordinator.retryPendingNow()
        coordinator.retryPendingNow()

        assertEquals(3, scheduler.ensureCount.get())
        // Sequential triggers: each run finishes (vacuously — nothing pending)
        // before the next, so there is nothing to coalesce here.
        assertEquals(3, contacts.retryCount.get())
        assertEquals(3, sos.retryCount.get())
    }

    @Test
    fun `a connectivity-regained trigger also ensures the sync work is queued`() {
        val contacts = CountingContactsBackup()
        val sos = CountingSosBackup()
        val monitor = FakeConnectivityMonitor()
        val scheduler = FakeSyncWorkScheduler()
        val coordinator = SyncRetryCoordinator(contacts, sos, monitor, Dispatchers.Unconfined, scheduler)

        coordinator.start()
        val afterStart = scheduler.ensureCount.get()
        monitor.simulateConnectivityRegained()

        assertEquals(1, afterStart)
        assertEquals(2, scheduler.ensureCount.get()) // app-start enqueue + trigger-path enqueue
    }
}

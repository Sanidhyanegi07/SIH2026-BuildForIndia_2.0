package com.example.georescux.work

import com.example.georescux.core.result.AuthResult
import com.example.georescux.data.sync.ContactsBackup
import com.example.georescux.data.sync.ConnectivityMonitor
import com.example.georescux.data.sync.SosBackup
import com.example.georescux.data.sync.SyncRetryCoordinator
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 8: verifies the [SyncWorker] trigger contract on the JVM (no real
 * WorkManager environment): the worker's whole body is ONE trigger into
 * the existing [SyncRetryCoordinator] — never into the retry methods
 * directly, never a second executor — and the unique work name matches the
 * design pinned in docs/STAGE8_SYNC_RETRY_DESIGN.md.
 */
class SyncWorkerTest {

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

    private class NoopConnectivityMonitor : ConnectivityMonitor {
        override fun start(onRegained: () -> Unit) = Unit
        override fun stop() = Unit
    }

    @Test
    fun `the worker trigger goes only through the coordinator to both existing retry methods`() {
        val contacts = CountingContactsBackup()
        val sos = CountingSosBackup()
        val coordinator = SyncRetryCoordinator(contacts, sos, NoopConnectivityMonitor(), Dispatchers.Unconfined)

        SyncWorker.triggerPendingRetry(coordinator)

        assertEquals(1, contacts.retryCount.get()) // ContactsBackup.retryPendingBackup invoked
        assertEquals(1, sos.retryCount.get()) // SosBackup.retryPendingBackups invoked
    }

    @Test
    fun `the unique work name matches the pinned design`() {
        assertEquals("georescux-sync-pending-retry", SyncWorker.UNIQUE_WORK_NAME)
    }
}

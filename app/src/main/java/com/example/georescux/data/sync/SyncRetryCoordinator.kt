package com.example.georescux.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 7B-3 Step 4: fires the existing pending-backup retries
 * ([ContactsBackup.retryPendingBackup], [SosBackup.retryPendingBackups])
 * from process-level triggers — app startup and connectivity regained —
 * coalescing triggers so overlapping events cannot stack up uncontrolled
 * retry jobs.
 *
 * The in-memory Mutex/flag here only serialize execution; the
 * [SyncStateStore] remains the durable persistence mechanism, and the
 * retry methods themselves decide (per current UID) whether anything is
 * pending. Every run re-checks pending state, so a run with nothing
 * pending performs no cloud work at all.
 *
 * Stage 8: a [SyncWorkScheduler] (WorkManager-backed when installed) adds
 * OS-guaranteed scheduling — the coordinator enqueues the unique
 * network-constrained work on [start] and on every [retryPendingNow]
 * trigger; the queued worker is itself just another trigger into
 * [retryPendingNow]. The coordinator remains the sole
 * executor/serializer; WorkManager is scheduling only.
 */
class SyncRetryCoordinator(
    private val contactsBackup: ContactsBackup,
    private val sosBackup: SosBackup,
    private val connectivityMonitor: ConnectivityMonitor,
    retryDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val workScheduler: SyncWorkScheduler? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + retryDispatcher)
    private val retryMutex = Mutex()
    private val retryAgain = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    /**
     * Registers the connectivity trigger. Idempotent: repeated calls never
     * register a second callback. Pairs with [stop].
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        connectivityMonitor.start { retryPendingNow() }
        workScheduler?.ensureQueued()
    }

    /** Unregisters the connectivity trigger. Safe to call repeatedly. */
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        connectivityMonitor.stop()
    }

    /**
     * Ensures the Stage 8 background sync work is queued with the OS
     * (idempotent by `ExistingWorkPolicy.KEEP` in the scheduler). Called by
     * [start] and by every [retryPendingNow] trigger so a pending marker
     * always has a network-constrained attempt waiting for it, even if the
     * process dies first. No-op when no [SyncWorkScheduler] is installed.
     */
    fun ensureQueued() {
        workScheduler?.ensureQueued()
    }

    /**
     * One coalesced retry run. Any number of concurrent triggers produce at
     * most one running job plus one queued follow-up: while a run is in
     * progress, extra triggers only ask it to run once more when it finishes
     * instead of starting overlapping jobs. After a run finishes, a genuine
     * later trigger starts a fresh run.
     */
    fun retryPendingNow() {
        workScheduler?.ensureQueued()
        scope.launch {
            if (!retryMutex.tryLock()) {
                // A run is already in progress: coalesce this trigger into
                // that run's follow-up instead of stacking another job.
                retryAgain.set(true)
                return@launch
            }
            try {
                do {
                    retryAgain.set(false)
                    try {
                        contactsBackup.retryPendingBackup()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Contained: pending state remains for the next trigger.
                    }
                    try {
                        sosBackup.retryPendingBackups()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Contained: pending state remains for the next trigger.
                    }
                } while (retryAgain.getAndSet(false))
            } finally {
                retryMutex.unlock()
            }
        }
    }
}

package com.example.georescux.data.sync

import com.example.georescux.domain.repository.AuthRepository
import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decorator around the local SOS repository (Stages 1–5B unchanged) that
 * mirrors emergency records to Firebase as a best-effort backup (Stage 6),
 * with a durable retry trail (Stage 7B-3):
 *
 * - Local storage stays the source of truth and is written FIRST.
 * - One-way push only: no cloud-to-local pull, no conflict resolution.
 * - Upload failures are contained, recorded durably as pending state
 *   ([SyncStateStore]), and retried by [retryPendingBackups] — they never
 *   fail a local SOS operation.
 * - Signed-out users skip uploads entirely; each account's pending alerts
 *   wait for their own session.
 * - Pushes are serialized with a Mutex and the retry re-reads the CURRENT
 *   local record inside the lock, so a retry can never overwrite a newer
 *   push with an older snapshot (the deterministic per-alert node makes
 *   the last push win).
 */
class SyncingSosRepository(
    private val local: SosRepository,
    private val authRepository: AuthRepository,
    private val cloudDataSource: SosCloudDataSource,
    private val syncStateStore: SyncStateStore,
    backupDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SosRepository, SosBackup {

    private val backupScope = CoroutineScope(SupervisorJob() + backupDispatcher)
    private val backupMutex = Mutex()

    override fun getActiveEmergency(): SosEmergency? = local.getActiveEmergency()

    override fun getHistory(): List<SosEmergency> = local.getHistory()

    override fun startEmergency(id: String, startedAtMs: Long): SosEmergency? {
        val result = local.startEmergency(id, startedAtMs)
        if (result != null) {
            scheduleBackup(result)
        }
        return result
    }

    override fun updateActiveEmergencyLocation(location: SosLocation?, status: SosLocationStatus) {
        local.updateActiveEmergencyLocation(location, status)
        // The local update is already throttled by SosLocationPolicy, so the
        // cloud upload piggybacks on the same ~60s rhythm.
        val active = local.getActiveEmergency()
        if (active != null) {
            scheduleBackup(active)
        }
    }

    override fun completeEmergency(stoppedAtMs: Long): SosEmergency? {
        val completed = local.completeEmergency(stoppedAtMs)
        if (completed != null) {
            scheduleBackup(completed)
        }
        return completed
    }

    override suspend fun backupNow(): Boolean {
        // Signed-out users skip the backup entirely.
        val uid = authRepository.currentUserId ?: return false
        // Order matters: the history upsert mirrors the whole node, then the
        // active record (which is NOT part of local history) is re-added.
        val history = local.getHistory()
        val historyUploaded = cloudDataSource.upsertHistory(history)
        val active = local.getActiveEmergency()
        val activeUploaded = active?.let { cloudDataSource.upsertEmergency(it) } ?: true
        // Durable bookkeeping: a successful mirror proves every history
        // record reached the cloud; a failed one marks them all pending so
        // no failed push is silently lost.
        if (historyUploaded) {
            history.forEach { syncStateStore.clearSosAlertPending(uid, it.id) }
        } else {
            history.forEach { syncStateStore.setSosAlertPending(uid, it.id, pending = true) }
        }
        if (active != null) {
            syncStateStore.setSosAlertPending(uid, active.id, pending = !activeUploaded)
        }
        return historyUploaded && activeUploaded
    }

    override fun retryPendingBackups(): Boolean {
        // Signed-out users have no backup pending for any account.
        val uid = authRepository.currentUserId ?: return true
        val pendingIds = syncStateStore.pendingSosAlertIds(uid)
        if (pendingIds.isEmpty()) return true // nothing pending: vacuous success
        backupScope.launch {
            try {
                backupMutex.withLock {
                    for (alertId in pendingIds) {
                        // Re-read the CURRENT local state inside the lock so a
                        // retry never recreates a stale historical payload.
                        val record = local.getActiveEmergency()?.takeIf { it.id == alertId }
                            ?: local.getHistory().firstOrNull { it.id == alertId }
                            ?: continue // no local record: leave its pending state untouched
                        val uploaded = tryUpload(record)
                        if (uploaded) syncStateStore.clearSosAlertPending(uid, alertId)
                        // Failure: the alert stays pending for the next trigger.
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Contained; pending alerts remain for the next trigger.
            }
        }
        return true // retry accepted (asynchronous)
    }

    private fun scheduleBackup(record: SosEmergency) {
        // Signed-out users skip uploads entirely.
        if (authRepository.currentUserId == null) return
        backupScope.launch {
            try {
                tryUpload(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort backup: failures are contained here and never
                // affect local SOS functionality.
            }
        }
    }

    /**
     * One best-effort push of [record] to its deterministic per-alert node.
     * The outcome is recorded durably: success clears the alert's pending
     * state, failure marks it pending. Returns the upload outcome; only
     * ever throws CancellationException.
     */
    private suspend fun tryUpload(record: SosEmergency): Boolean {
        val uid = authRepository.currentUserId ?: return false
        val uploaded = try {
            cloudDataSource.upsertEmergency(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        syncStateStore.setSosAlertPending(uid, record.id, pending = !uploaded)
        return uploaded
    }
}

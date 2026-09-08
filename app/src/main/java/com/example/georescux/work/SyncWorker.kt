package com.example.georescux.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.georescux.GeoRescuXApplication
import com.example.georescux.data.sync.SyncRetryCoordinator

/**
 * Stage 8 (docs/STAGE8_SYNC_RETRY_DESIGN.md): the WorkManager-scheduled
 * trigger for the durable pending sync. SCHEDULING ONLY — the worker's
 * entire job is one trigger into [SyncRetryCoordinator.retryPendingNow],
 * which remains the sole executor/serializer (app-start, connectivity and
 * local-change triggers are unchanged and all flow through it).
 *
 * The worker ALWAYS returns [Result.success]: failure containment lives in
 * the retry methods, whose outcome is recorded durably in SyncStateStore.
 * The retry run itself is fire-and-forget; if the process dies mid-run the
 * markers survive and the next enqueued work / trigger retries.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val coordinator = (applicationContext as? GeoRescuXApplication)
            ?.appContainer
            ?.syncRetryCoordinator
        if (coordinator != null) {
            triggerPendingRetry(coordinator)
        }
        return Result.success()
    }

    companion object {
        /** Unique work name pinned by docs/STAGE8_SYNC_RETRY_DESIGN.md. */
        const val UNIQUE_WORK_NAME = "georescux-sync-pending-retry"

        /**
         * The worker's whole body, extracted as a JVM-testable seam: the
         * trigger goes ONLY into the existing coordinator — never into the
         * retry methods or a second executor.
         */
        internal fun triggerPendingRetry(coordinator: SyncRetryCoordinator) {
            coordinator.retryPendingNow()
        }
    }
}

package com.example.georescux.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.georescux.data.sync.SyncWorkScheduler
import java.util.concurrent.TimeUnit

/**
 * Stage 8 (docs/STAGE8_SYNC_RETRY_DESIGN.md): the WorkManager-backed
 * [SyncWorkScheduler]. Scheduling only — it queues the unique one-time
 * [SyncWorker] trigger; the coordinator remains the sole executor.
 *
 * Exactly as pinned by the design document:
 * - unique name [SyncWorker.UNIQUE_WORK_NAME], `ExistingWorkPolicy.KEEP`
 *   (at most one queued/one running; repeated calls never stack work),
 * - constraint `NetworkType.CONNECTED`,
 * - backoff `EXPONENTIAL`, 30 s initial (dormant in normal flow — the
 *   worker always succeeds),
 * - one-time work only; no periodic work in v1.
 */
class WorkManagerSyncScheduler(context: Context) : SyncWorkScheduler {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun ensureQueued() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            SyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

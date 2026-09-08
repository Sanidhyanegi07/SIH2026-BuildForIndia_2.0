package com.example.georescux.data.sync

/**
 * Stage 8: ensures the durable background sync work is queued with the OS
 * (see docs/STAGE8_SYNC_RETRY_DESIGN.md). Scheduling only — the queued work
 * is a trigger into [SyncRetryCoordinator.retryPendingNow], which remains
 * the sole executor/serializer of retry runs.
 *
 * Abstracted so the coordinator stays JVM-testable;
 * `WorkManagerSyncScheduler` is the Android implementation. Idempotent by
 * contract: repeated calls must never stack duplicate work
 * (`ExistingWorkPolicy.KEEP` in the implementation).
 */
interface SyncWorkScheduler {
    fun ensureQueued()
}

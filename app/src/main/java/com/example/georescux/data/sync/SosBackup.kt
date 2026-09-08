package com.example.georescux.data.sync

/**
 * Best-effort Firebase backup of local SOS emergency records.
 * Never modifies local SOS data; local storage remains the source of truth.
 */
interface SosBackup {
    /**
     * Uploads the current active emergency (if any) and the complete local
     * history. Returns false when signed out or when any upload failed.
     * Never throws for offline/permission/missing-database failures.
     */
    suspend fun backupNow(): Boolean

    /**
     * Retries previously failed SOS uploads, if any are pending. Every
     * pending alert id is re-read from CURRENT local state and pushed to
     * its deterministic node; confirmed successes clear that alert's
     * pending state, failures leave it pending. Returns true when nothing
     * is pending or the retry was accepted (asynchronous); the outcome is
     * observable via the pending state. Signed-out users are a vacuous
     * success with no cloud operation.
     */
    fun retryPendingBackups(): Boolean
}

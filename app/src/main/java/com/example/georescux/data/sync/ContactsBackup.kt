package com.example.georescux.data.sync

/**
 * Best-effort Firebase backup of locally stored emergency contacts.
 * Local storage always remains the source of truth.
 */
interface ContactsBackup {
    /**
     * Pushes the complete local contact list for the signed-in user.
     * Returns false when signed out or when the backup failed.
     * Never throws for offline/permission/missing-database failures.
     */
    suspend fun backupNow(): Boolean

    /**
     * Retries a previously failed Contacts backup, if one is pending.
     * Returns true when nothing is pending or the retry was accepted
     * (asynchronous); the outcome is observable via the pending flag.
     */
    fun retryPendingBackup(): Boolean
}

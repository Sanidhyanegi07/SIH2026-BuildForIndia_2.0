package com.example.georescux.data.sync

import com.example.georescux.domain.contacts.EmergencyContact
import com.example.georescux.domain.repository.AuthRepository
import com.example.georescux.domain.repository.ContactsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decorator around the local contacts repository (Stage 3) that mirrors
 * every local change to Firebase as a best-effort backup (Stage 5B), with
 * a durable retry trail (Stage 7B-3):
 *
 * - Local storage stays the source of truth and is written FIRST.
 * - One-way push only: no cloud-to-local pull, no conflict resolution.
 * - Backup failures are contained, recorded durably as pending state
 *   ([SyncStateStore]), and retried on the next app start, when
 *   connectivity returns, or on the next local change.
 * - Local contacts operations NEVER fail because of a backup problem.
 * - Signed-out users skip the backup entirely; no pending state is
 *   mutated, and each account's pending state waits for its own session.
 * - Pushes are serialized with a Mutex so rapid local changes converge on
 *   the latest local list (the full-mirror payload makes intermediate
 *   states irrelevant).
 */
class SyncingContactsRepository(
    private val local: ContactsRepository,
    private val authRepository: AuthRepository,
    private val cloudDataSource: ContactsCloudDataSource,
    private val syncStateStore: SyncStateStore,
    backupDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ContactsRepository, ContactsBackup {

    private val backupScope = CoroutineScope(SupervisorJob() + backupDispatcher)
    private val backupMutex = Mutex()

    override fun getContacts(): List<EmergencyContact> = local.getContacts()

    override fun saveContact(contact: EmergencyContact): List<EmergencyContact> {
        val result = local.saveContact(contact)
        scheduleBackup()
        return result
    }

    override fun deleteContact(id: String): List<EmergencyContact> {
        val result = local.deleteContact(id)
        scheduleBackup()
        return result
    }

    override suspend fun backupNow(): Boolean {
        // Signed-out users skip the backup entirely and leave pending
        // state untouched (no UID to scope it to).
        val uid = authRepository.currentUserId ?: return false
        // The Mutex serializes pushes so rapid local changes converge on the
        // latest list; the pending flag — not the Mutex — is the durable
        // retry record.
        return backupMutex.withLock {
            val uploaded = cloudDataSource.uploadContacts(local.getContacts())
            // Record the outcome durably so a failed push is retried on the
            // next trigger instead of being silently lost.
            syncStateStore.setContactBackupPending(uid, pending = !uploaded)
            uploaded
        }
    }

    override fun retryPendingBackup(): Boolean {
        // Signed-out users have no backup pending for any account.
        val uid = authRepository.currentUserId ?: return true
        if (!syncStateStore.isContactBackupPending(uid)) return true // nothing pending: vacuous success
        backupScope.launch {
            try {
                backupNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Contained; the pending flag remains set for the next trigger.
            }
        }
        return true // retry accepted (asynchronous)
    }

    private fun scheduleBackup() {
        backupScope.launch {
            try {
                backupNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Contained; the pending flag remains set for the next trigger.
            }
        }
    }
}

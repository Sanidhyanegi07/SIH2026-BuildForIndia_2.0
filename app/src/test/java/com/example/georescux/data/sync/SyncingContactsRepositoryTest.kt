package com.example.georescux.data.sync

import com.example.georescux.domain.contacts.ContactPriority
import com.example.georescux.domain.contacts.EmergencyContact
import com.example.georescux.domain.repository.AuthRepository
import com.example.georescux.domain.repository.ContactsRepository
import com.example.georescux.core.result.AuthResult
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Stage 5B backup decorator: local operations succeed first
 * and are mirrored best-effort to the cloud data source. Firebase failures
 * never fail a local operation, signed-out users skip the backup, and an
 * empty list is pushed so deletions propagate.
 *
 * Stage 7B-3 additionally verifies the durable pending state: failed
 * backups mark the UID's Contacts pending flag, successful ones clear it,
 * and [SyncingContactsRepository.retryPendingBackup] re-pushes what is
 * pending.
 */
class SyncingContactsRepositoryTest {

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

    private class FakeCloudDataSource : ContactsCloudDataSource {
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

    private fun contact(id: String, priority: ContactPriority) = EmergencyContact(
        id = id,
        name = "Name $id",
        phoneNumber = "9876543210",
        relationship = "Friend",
        priority = priority,
    )

    private data class Harness(
        val repo: SyncingContactsRepository,
        val local: FakeLocalContactsRepository,
        val cloud: FakeCloudDataSource,
        val syncStateStore: SyncStateStoreFake,
    )

    private fun repository(
        uid: String? = "user-a",
        cloud: FakeCloudDataSource = FakeCloudDataSource(),
        local: FakeLocalContactsRepository = FakeLocalContactsRepository(),
        syncStateStore: SyncStateStoreFake = SyncStateStoreFake(),
    ): Harness = Harness(
        repo = SyncingContactsRepository(
            local = local,
            authRepository = FakeAuthRepository(uid),
            cloudDataSource = cloud,
            syncStateStore = syncStateStore,
            backupDispatcher = Dispatchers.Unconfined,
        ),
        local = local,
        cloud = cloud,
        syncStateStore = syncStateStore,
    )

    @Test
    fun `successful local save triggers a cloud backup`() {
        val (repo, local, cloud) = repository()

        val result = repo.saveContact(contact("c1", ContactPriority.PRIMARY))

        assertEquals(listOf(contact("c1", ContactPriority.PRIMARY)), result)
        assertEquals(listOf(contact("c1", ContactPriority.PRIMARY)), local.getContacts())
        assertEquals(1, cloud.uploadCount)
        assertEquals(listOf(contact("c1", ContactPriority.PRIMARY)), cloud.lastUpload)
    }

    @Test
    fun `successful local delete triggers a cloud backup with the empty list`() {
        val (repo, local, cloud) = repository()
        repo.saveContact(contact("c1", ContactPriority.SECONDARY))

        val result = repo.deleteContact("c1")

        assertTrue(result.isEmpty())
        assertTrue(local.getContacts().isEmpty())
        assertEquals(2, cloud.uploadCount) // save + delete
        assertTrue(cloud.lastUpload!!.isEmpty()) // deletion propagated to the cloud
    }

    @Test
    fun `firebase backup failure does not fail a local save`() {
        val (repo, local, cloud) = repository(cloud = FakeCloudDataSource().apply { failUploads = true })

        val result = repo.saveContact(contact("c1", ContactPriority.SECONDARY))

        assertEquals(listOf(contact("c1", ContactPriority.SECONDARY)), result)
        assertEquals(listOf(contact("c1", ContactPriority.SECONDARY)), local.getContacts())
        assertNull(cloud.lastUpload) // the failed upload stored nothing
    }

    @Test
    fun `firebase backup failure does not fail a local delete`() {
        val (repo, local, cloud) = repository(cloud = FakeCloudDataSource().apply { failUploads = true })
        repo.saveContact(contact("c1", ContactPriority.SECONDARY))

        val result = repo.deleteContact("c1")

        assertTrue(result.isEmpty())
        assertTrue(local.getContacts().isEmpty())
    }

    @Test
    fun `signed-out users skip the cloud backup entirely`() {
        val (repo, local, cloud) = repository(uid = null)

        val result = repo.saveContact(contact("c1", ContactPriority.SECONDARY))

        assertEquals(0, cloud.uploadCount) // backup skipped: nothing was uploaded
    }

    @Test
    fun `an empty contact list can be pushed`() = kotlinx.coroutines.runBlocking {
        val (repo, local, cloud) = repository()

        assertTrue(repo.backupNow())

        assertTrue(cloud.lastUpload!!.isEmpty())
        assertTrue(local.getContacts().isEmpty())
    }

    @Test
    fun `the full contact list is pushed deterministically`() = kotlinx.coroutines.runBlocking {
        val (repo, local, cloud) = repository()
        repo.saveContact(contact("c2", ContactPriority.SECONDARY))
        repo.saveContact(contact("c1", ContactPriority.PRIMARY))
        repo.saveContact(contact("c3", ContactPriority.SECONDARY))

        assertTrue(repo.backupNow())

        assertEquals(listOf("c2", "c1", "c3"), cloud.lastUpload!!.map { it.id })
        assertEquals(1, cloud.lastUpload!!.count { it.priority == ContactPriority.PRIMARY })
    }

    @Test
    fun `read operations still come from local storage`() {
        val (repo, local, cloud) = repository()
        local.localContacts.add(contact("c1", ContactPriority.SECONDARY))

        assertEquals(listOf(contact("c1", ContactPriority.SECONDARY)), repo.getContacts())
        assertEquals(0, cloud.uploadCount) // reads never touch the cloud
    }

    @Test
    fun `app-start backup does not affect local data`() = kotlinx.coroutines.runBlocking {
        val (repo, local, cloud) = repository()
        local.localContacts.add(contact("c1", ContactPriority.SECONDARY))

        assertTrue(repo.backupNow())

        assertEquals(listOf(contact("c1", ContactPriority.SECONDARY)), local.getContacts())
        assertEquals(listOf(contact("c1", ContactPriority.SECONDARY)), cloud.lastUpload)
    }

    // ---- Stage 7B-3: durable pending state + retry ----

    @Test
    fun `a successful backup clears the contacts pending flag`() = kotlinx.coroutines.runBlocking {
        val (repo, _, _, syncStateStore) = repository()
        syncStateStore.setContactBackupPending("user-a", pending = true)

        assertTrue(repo.backupNow())

        assertFalse(syncStateStore.isContactBackupPending("user-a"))
    }

    @Test
    fun `a failed backup marks the contacts pending flag`() = kotlinx.coroutines.runBlocking {
        val (repo, _, _, syncStateStore) = repository(cloud = FakeCloudDataSource().apply { failUploads = true })

        assertFalse(repo.backupNow())

        assertTrue(syncStateStore.isContactBackupPending("user-a"))
    }

    @Test
    fun `a failed scheduled backup after a local save marks the pending flag`() {
        val (repo, _, _, syncStateStore) = repository(cloud = FakeCloudDataSource().apply { failUploads = true })

        repo.saveContact(contact("c1", ContactPriority.SECONDARY))

        assertTrue(syncStateStore.isContactBackupPending("user-a"))
    }

    @Test
    fun `a successful scheduled backup after a local save leaves nothing pending`() {
        val (repo, _, _, syncStateStore) = repository()

        repo.saveContact(contact("c1", ContactPriority.SECONDARY))

        assertFalse(syncStateStore.isContactBackupPending("user-a"))
    }

    @Test
    fun `retryPendingBackup with nothing pending is a vacuous success`() {
        val (repo, _, cloud, _) = repository()

        assertTrue(repo.retryPendingBackup())

        assertEquals(0, cloud.uploadCount)
    }

    @Test
    fun `retryPendingBackup pushes the local list and clears the pending flag`() {
        val (repo, local, cloud, syncStateStore) = repository()
        local.localContacts.add(contact("c1", ContactPriority.SECONDARY))
        syncStateStore.setContactBackupPending("user-a", pending = true)

        assertTrue(repo.retryPendingBackup())

        assertEquals(1, cloud.uploadCount)
        assertEquals(listOf(contact("c1", ContactPriority.SECONDARY)), cloud.lastUpload)
        assertFalse(syncStateStore.isContactBackupPending("user-a"))
    }

    @Test
    fun `signed-out users have no pending backup to retry`() {
        val (repo, _, cloud, _) = repository(uid = null)

        assertTrue(repo.retryPendingBackup())

        assertEquals(0, cloud.uploadCount)
    }
}

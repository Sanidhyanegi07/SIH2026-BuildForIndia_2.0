package com.example.georescux.data.contacts

import com.example.georescux.domain.contacts.ContactPriority
import com.example.georescux.domain.contacts.EmergencyContact
import com.example.georescux.domain.repository.AuthRepository
import com.example.georescux.domain.repository.ContactsRepository
import com.example.georescux.core.result.AuthResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the emergency-contacts repository: CRUD, the single-PRIMARY
 * rule at the write path, per-UID isolation, and signed-out behavior.
 */
class ContactsRepositoryImplTest {

    private class InMemoryContactStore : ContactLocalStore {
        val saved = mutableMapOf<String, List<EmergencyContact>>()
        override fun loadContacts(uid: String): List<EmergencyContact> = saved[uid] ?: emptyList()
        override fun saveContacts(uid: String, contacts: List<EmergencyContact>) {
            saved[uid] = contacts
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

    private fun contact(id: String, priority: ContactPriority) = EmergencyContact(
        id = id,
        name = "Name $id",
        phoneNumber = "123",
        relationship = "Friend",
        priority = priority,
    )

    @Test
    fun `repository saves and reads contacts for the signed-in user`() {
        val repository = ContactsRepositoryImpl(InMemoryContactStore(), FakeAuthRepository("user-a"))

        val result = repository.saveContact(contact("c1", ContactPriority.PRIMARY))

        assertEquals(listOf(contact("c1", ContactPriority.PRIMARY)), result)
        assertEquals(listOf(contact("c1", ContactPriority.PRIMARY)), repository.getContacts())
    }

    @Test
    fun `repository deletes a contact by id`() {
        val repository = ContactsRepositoryImpl(InMemoryContactStore(), FakeAuthRepository("user-a"))
        repository.saveContact(contact("c1", ContactPriority.SECONDARY))
        repository.saveContact(contact("c2", ContactPriority.SECONDARY))

        val result = repository.deleteContact("c1")

        assertEquals(listOf("c2"), result.map { it.id })
    }

    @Test
    fun `multiple contacts are supported`() {
        val repository = ContactsRepositoryImpl(InMemoryContactStore(), FakeAuthRepository("user-a"))

        repository.saveContact(contact("c1", ContactPriority.PRIMARY))
        repository.saveContact(contact("c2", ContactPriority.SECONDARY))
        repository.saveContact(contact("c3", ContactPriority.SECONDARY))

        assertEquals(3, repository.getContacts().size)
    }

    @Test
    fun `saving a primary demotes the previous primary at the write path`() {
        val repository = ContactsRepositoryImpl(InMemoryContactStore(), FakeAuthRepository("user-a"))
        repository.saveContact(contact("c1", ContactPriority.PRIMARY))

        val result = repository.saveContact(contact("c2", ContactPriority.PRIMARY))

        assertEquals(
            ContactPriority.SECONDARY,
            result.first { it.id == "c1" }.priority
        )
        assertEquals(1, result.count { it.priority == ContactPriority.PRIMARY })
    }

    @Test
    fun `deleting the primary leaves zero primary contacts`() {
        val repository = ContactsRepositoryImpl(InMemoryContactStore(), FakeAuthRepository("user-a"))
        repository.saveContact(contact("c1", ContactPriority.PRIMARY))
        repository.saveContact(contact("c2", ContactPriority.SECONDARY))

        val result = repository.deleteContact("c1")

        assertEquals(0, result.count { it.priority == ContactPriority.PRIMARY })
        assertEquals(listOf("c2"), result.map { it.id })
    }

    @Test
    fun `contacts are isolated between users`() {
        val store = InMemoryContactStore()
        val auth = FakeAuthRepository("user-a")
        val repository = ContactsRepositoryImpl(store, auth)

        val contactA = contact("a1", ContactPriority.PRIMARY)
        repository.saveContact(contactA)

        // Switch to user B: no shared contacts, and B can save their own list.
        auth.uid = "user-b"
        assertTrue(repository.getContacts().isEmpty())
        repository.saveContact(contact("b1", ContactPriority.SECONDARY))

        // Switch back to user A: A's list is intact and contains no B contacts.
        auth.uid = "user-a"
        assertEquals(listOf(contactA), repository.getContacts())
        assertTrue(store.saved["user-b"]!!.none { it.id == contactA.id })
    }

    @Test
    fun `signed-out users get empty results and no writes`() {
        val store = InMemoryContactStore()
        val repository = ContactsRepositoryImpl(store, FakeAuthRepository(null))

        assertTrue(repository.getContacts().isEmpty())

        val result = repository.saveContact(contact("c1", ContactPriority.PRIMARY))
        assertTrue(result.isEmpty())

        assertTrue(repository.deleteContact("c1").isEmpty())

        // Nothing was persisted anywhere.
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `signing out clears visibility of the previous user's contacts`() {
        val auth = FakeAuthRepository("user-a")
        val repository = ContactsRepositoryImpl(InMemoryContactStore(), auth)
        repository.saveContact(contact("c1", ContactPriority.PRIMARY))

        auth.uid = null

        assertTrue(repository.getContacts().isEmpty())
    }
}

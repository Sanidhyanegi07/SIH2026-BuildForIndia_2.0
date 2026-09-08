package com.example.georescux.ui.contacts

import com.example.georescux.domain.contacts.ContactPriority
import com.example.georescux.domain.contacts.EmergencyContact
import com.example.georescux.domain.repository.ContactsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the contacts ViewModel: initial loading, add, and delete all
 * update the exposed StateFlow through the repository.
 */
class ContactsViewModelTest {

    private class FakeContactsRepository(start: List<EmergencyContact>) : ContactsRepository {
        var savedContacts: List<EmergencyContact> = start
        var saveCount = 0
        var deleteCount = 0

        override fun getContacts(): List<EmergencyContact> = savedContacts

        override fun saveContact(contact: EmergencyContact): List<EmergencyContact> {
            saveCount++
            savedContacts = savedContacts.filterNot { it.id == contact.id } + contact
            return savedContacts
        }

        override fun deleteContact(id: String): List<EmergencyContact> {
            deleteCount++
            savedContacts = savedContacts.filterNot { it.id == id }
            return savedContacts
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
    fun `contacts are loaded from the repository when initialized`() {
        val seed = listOf(contact("c1", ContactPriority.PRIMARY))
        val viewModel = ContactsViewModel(FakeContactsRepository(seed))

        assertEquals(seed, viewModel.contacts.value)
    }

    @Test
    fun `adding a contact updates the exposed state flow`() {
        val repository = FakeContactsRepository(emptyList())
        val viewModel = ContactsViewModel(repository)

        viewModel.addContact(contact("c1", ContactPriority.PRIMARY))

        assertEquals(listOf(contact("c1", ContactPriority.PRIMARY)), viewModel.contacts.value)
        assertEquals(1, repository.saveCount)
    }

    @Test
    fun `deleting a contact updates the exposed state flow`() {
        val viewModel = ContactsViewModel(FakeContactsRepository(listOf(contact("c1", ContactPriority.SECONDARY))))

        viewModel.deleteContact("c1")

        assertTrue(viewModel.contacts.value.isEmpty())
    }
}

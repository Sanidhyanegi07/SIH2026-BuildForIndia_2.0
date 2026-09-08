package com.example.georescux.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.domain.contacts.EmergencyContact
import com.example.georescux.domain.repository.ContactsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the signed-in user's emergency contacts. All changes go through
 * [ContactsRepository] (offline, per-user) and are exposed as a StateFlow;
 * the ViewModel contains no storage, Firebase, or primary-rule logic.
 */
class ContactsViewModel(private val contactsRepository: ContactsRepository) : ViewModel() {

    private val _contacts = MutableStateFlow(contactsRepository.getContacts())
    val contacts: StateFlow<List<EmergencyContact>> = _contacts.asStateFlow()

    fun addContact(contact: EmergencyContact) {
        _contacts.value = contactsRepository.saveContact(contact)
    }

    fun deleteContact(id: String) {
        _contacts.value = contactsRepository.deleteContact(id)
    }

    class Factory(private val contactsRepository: ContactsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ContactsViewModel(contactsRepository) as T
        }
    }
}

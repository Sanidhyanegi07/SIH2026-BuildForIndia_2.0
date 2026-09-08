package com.example.georescux.data.contacts

import com.example.georescux.domain.contacts.ContactRules
import com.example.georescux.domain.contacts.EmergencyContact
import com.example.georescux.domain.repository.AuthRepository
import com.example.georescux.domain.repository.ContactsRepository

/**
 * Offline-first emergency-contacts repository.
 *
 * - All data lives on the device, scoped per Firebase UID: user A never
 *   sees user B's contacts.
 * - The repository is the single write gateway enforcing the
 *   "at most one PRIMARY contact" rule (via [ContactRules]).
 * - Signed-out users get empty results and no writes.
 */
class ContactsRepositoryImpl(
    private val contactStore: ContactLocalStore,
    private val authRepository: AuthRepository,
) : ContactsRepository {

    override fun getContacts(): List<EmergencyContact> {
        val uid = authRepository.currentUserId ?: return emptyList()
        return contactStore.loadContacts(uid)
    }

    override fun saveContact(contact: EmergencyContact): List<EmergencyContact> {
        val uid = authRepository.currentUserId ?: return emptyList()
        val result = ContactRules.enforceSinglePrimary(contactStore.loadContacts(uid), contact)
        contactStore.saveContacts(uid, result)
        return result
    }

    override fun deleteContact(id: String): List<EmergencyContact> {
        val uid = authRepository.currentUserId ?: return emptyList()
        val result = contactStore.loadContacts(uid).filterNot { it.id == id }
        contactStore.saveContacts(uid, result)
        return result
    }
}

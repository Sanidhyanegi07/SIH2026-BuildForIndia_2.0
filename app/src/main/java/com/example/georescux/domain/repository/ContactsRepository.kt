package com.example.georescux.domain.repository

import com.example.georescux.domain.contacts.EmergencyContact

/**
 * Emergency-contacts operations for the signed-in user. Local-only
 * (offline-first); every method returns the resulting contact list.
 */
interface ContactsRepository {
    /** All contacts of the signed-in user, or an empty list when signed out. */
    fun getContacts(): List<EmergencyContact>

    /**
     * Saves a contact (new or update) and applies the single-PRIMARY rule.
     * Returns the resulting contact list.
     */
    fun saveContact(contact: EmergencyContact): List<EmergencyContact>

    /** Deletes a contact by id. Returns the resulting contact list. */
    fun deleteContact(id: String): List<EmergencyContact>
}

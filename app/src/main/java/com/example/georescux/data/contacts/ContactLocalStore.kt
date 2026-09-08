package com.example.georescux.data.contacts

import com.example.georescux.domain.contacts.EmergencyContact

/**
 * Local on-device storage for emergency contacts — deliberately not
 * Firebase, so contacts work completely offline. Contacts are stored
 * per Firebase UID, so different accounts never see each other's list.
 */
interface ContactLocalStore {
    fun loadContacts(uid: String): List<EmergencyContact>
    fun saveContacts(uid: String, contacts: List<EmergencyContact>)
}

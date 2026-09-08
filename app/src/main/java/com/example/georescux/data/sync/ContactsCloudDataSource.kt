package com.example.georescux.data.sync

import com.example.georescux.domain.contacts.EmergencyContact

/**
 * Pushes the complete contact list for the signed-in user to the cloud.
 * Implemented with Firebase Realtime Database; fakes replace it in tests.
 */
interface ContactsCloudDataSource {
    /**
     * Pushes the complete contact list. An empty list must also be pushed
     * so deletions propagate to the cloud.
     * Returns true on success; returns false on any failure (offline,
     * signed out, missing database, denied rules) — never throws.
     */
    suspend fun uploadContacts(contacts: List<EmergencyContact>): Boolean
}

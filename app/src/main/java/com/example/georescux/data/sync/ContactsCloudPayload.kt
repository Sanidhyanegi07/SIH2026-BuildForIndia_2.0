package com.example.georescux.data.sync

import com.example.georescux.domain.contacts.EmergencyContact

/**
 * Maps emergency contacts to the plain map structure stored in Firebase
 * Realtime Database. Pure Kotlin — JVM testable, no Android APIs.
 *
 * Deterministic: the same contact list always produces the same payload.
 */
object ContactsCloudPayload {

    /**
     * contacts/{uid}/{contactId} = { id, name, phoneNumber, relationship, priority }
     */
    fun fromContacts(contacts: List<EmergencyContact>): Map<String, Map<String, Any>> =
        contacts.associate { contact ->
            contact.id to mapOf(
                "id" to contact.id,
                "name" to contact.name,
                "phoneNumber" to contact.phoneNumber,
                "relationship" to contact.relationship,
                "priority" to contact.priority.name,
            )
        }
}

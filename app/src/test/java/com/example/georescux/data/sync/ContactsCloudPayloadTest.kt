package com.example.georescux.data.sync

import com.example.georescux.domain.contacts.ContactPriority
import com.example.georescux.domain.contacts.EmergencyContact
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsCloudPayloadTest {

    @Test
    fun `contact maps to the full cloud field set`() {
        val payload = ContactsCloudPayload.fromContacts(
            listOf(
                EmergencyContact(
                    id = "c1",
                    name = "Mom",
                    phoneNumber = "9876543210",
                    relationship = "Mother",
                    priority = ContactPriority.PRIMARY,
                )
            )
        )

        assertEquals(
            mapOf(
                "c1" to mapOf(
                    "id" to "c1",
                    "name" to "Mom",
                    "phoneNumber" to "9876543210",
                    "relationship" to "Mother",
                    "priority" to "PRIMARY",
                )
            ),
            payload
        )
    }

    @Test
    fun `mapping is deterministic for the same contact list`() {
        val contacts = listOf(
            EmergencyContact("c1", "Mom", "9876543210", "Mother", ContactPriority.PRIMARY),
            EmergencyContact("c2", "Dad", "9876543211", "Father", ContactPriority.SECONDARY),
        )

        assertEquals(ContactsCloudPayload.fromContacts(contacts), ContactsCloudPayload.fromContacts(contacts))
    }

    @Test
    fun `empty contact list maps to an empty payload`() {
        assertEquals(emptyMap<String, Map<String, Any>>(), ContactsCloudPayload.fromContacts(emptyList()))
    }
}

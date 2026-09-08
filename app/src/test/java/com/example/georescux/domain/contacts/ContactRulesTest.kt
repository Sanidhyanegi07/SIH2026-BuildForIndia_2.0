package com.example.georescux.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the "at most one PRIMARY contact" rule.
 */
class ContactRulesTest {

    private fun contact(id: String, priority: ContactPriority) = EmergencyContact(
        id = id,
        name = "Name $id",
        phoneNumber = "123",
        relationship = "Friend",
        priority = priority,
    )

    @Test
    fun `no primary contacts is a valid input and result`() {
        val contacts = listOf(contact("c1", ContactPriority.SECONDARY))
        val result = ContactRules.enforceSinglePrimary(contacts, contact("c2", ContactPriority.SECONDARY))

        assertEquals(2, result.size)
        assertEquals(0, result.count { it.priority == ContactPriority.PRIMARY })
    }

    @Test
    fun `one primary contact is kept as the only primary`() {
        val contacts = listOf(contact("c1", ContactPriority.PRIMARY))
        val result = ContactRules.enforceSinglePrimary(contacts, contact("c2", ContactPriority.SECONDARY))

        assertEquals(ContactPriority.PRIMARY, result.first { it.id == "c1" }.priority)
        assertEquals(1, result.count { it.priority == ContactPriority.PRIMARY })
    }

    @Test
    fun `saving a primary when the input already has multiple primaries leaves exactly one`() {
        val contacts = listOf(
            contact("c1", ContactPriority.PRIMARY),
            contact("c2", ContactPriority.PRIMARY),
        )

        val result = ContactRules.enforceSinglePrimary(contacts, contact("c3", ContactPriority.PRIMARY))

        assertEquals(ContactPriority.PRIMARY, result.first { it.id == "c3" }.priority)
        assertEquals(1, result.count { it.priority == ContactPriority.PRIMARY })
        assertEquals(3, result.size)
    }

    @Test
    fun `saving a primary demotes the previous primary`() {
        val contacts = listOf(
            contact("c1", ContactPriority.PRIMARY),
            contact("c2", ContactPriority.SECONDARY),
        )

        val result = ContactRules.enforceSinglePrimary(contacts, contact("c3", ContactPriority.PRIMARY))

        assertEquals(ContactPriority.SECONDARY, result.first { it.id == "c1" }.priority)
        assertEquals(ContactPriority.PRIMARY, result.first { it.id == "c3" }.priority)
        assertEquals(1, result.count { it.priority == ContactPriority.PRIMARY })
    }

    @Test
    fun `saving a contact with an existing id replaces it instead of duplicating`() {
        val contacts = listOf(contact("c1", ContactPriority.SECONDARY))

        val result = ContactRules.enforceSinglePrimary(
            contacts,
            contact("c1", ContactPriority.PRIMARY).copy(name = "Updated")
        )

        assertEquals(1, result.size)
        assertEquals("Updated", result[0].name)
        assertEquals(ContactPriority.PRIMARY, result[0].priority)
    }
}

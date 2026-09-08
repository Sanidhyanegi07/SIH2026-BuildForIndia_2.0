package com.example.georescux.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class EmergencyContactTest {

    @Test
    fun `contact stores all of its fields`() {
        val contact = EmergencyContact(
            id = "contact-1",
            name = "Mom",
            phoneNumber = "0500-123456",
            relationship = "Mother",
            priority = ContactPriority.PRIMARY,
        )

        assertEquals("contact-1", contact.id)
        assertEquals("Mom", contact.name)
        assertEquals("0500-123456", contact.phoneNumber)
        assertEquals("Mother", contact.relationship)
        assertEquals(ContactPriority.PRIMARY, contact.priority)
    }

    @Test
    fun `primary and secondary are the only priorities`() {
        assertEquals(
            setOf(ContactPriority.PRIMARY, ContactPriority.SECONDARY),
            ContactPriority.values().toSet()
        )
    }
}

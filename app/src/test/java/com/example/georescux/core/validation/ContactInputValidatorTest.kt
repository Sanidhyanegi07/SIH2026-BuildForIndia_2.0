package com.example.georescux.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the emergency-contact form validation rules, including the
 * reported bug: numeric-only or symbol-only values such as "000" and "!!!"
 * must be rejected for every field.
 */
class ContactInputValidatorTest {

    private val validName = "Mom"
    private val validPhone = "9876543210"
    private val validRelationship = "Mother"

    // ---- Name ----

    @Test
    fun `blank name is rejected`() {
        assertEquals(
            "Please enter a valid name (must contain letters).",
            ContactInputValidator.validateContact("", validPhone, validRelationship)
        )
        assertEquals(
            "Please enter a valid name (must contain letters).",
            ContactInputValidator.validateContact("   ", validPhone, validRelationship)
        )
    }

    @Test
    fun `numeric-only name is rejected`() {
        assertEquals(
            "Please enter a valid name (must contain letters).",
            ContactInputValidator.validateContact("000", validPhone, validRelationship)
        )
        assertEquals(
            "Please enter a valid name (must contain letters).",
            ContactInputValidator.validateContact("123", validPhone, validRelationship)
        )
    }

    @Test
    fun `symbol-only name is rejected`() {
        assertEquals(
            "Please enter a valid name (must contain letters).",
            ContactInputValidator.validateContact("!!!", validPhone, validRelationship)
        )
    }

    @Test
    fun `normal name with spaces is accepted`() {
        assertNull(ContactInputValidator.validateContact("John Doe", validPhone, validRelationship))
        assertNull(ContactInputValidator.validateContact("Amma", validPhone, validRelationship))
    }

    // ---- Phone ----

    @Test
    fun `blank phone is rejected`() {
        assertEquals(
            "Please enter a valid phone number.",
            ContactInputValidator.validateContact(validName, "", validRelationship)
        )
    }

    @Test
    fun `too short phone numbers are rejected`() {
        listOf("0", "00", "000", "123", "1234567", "987654321").forEach { phone ->
            assertEquals(
                "Please enter a valid phone number.",
                ContactInputValidator.validateContact(validName, phone, validRelationship)
            )
        }
    }

    @Test
    fun `non numeric phone numbers are rejected`() {
        listOf("abc", "!!!", "abc-def-ghij").forEach { phone ->
            assertEquals(
                "Please enter a valid phone number.",
                ContactInputValidator.validateContact(validName, phone, validRelationship)
            )
        }
    }

    @Test
    fun `ten digit indian mobiles starting with 6 to 9 are accepted`() {
        listOf("6765432109", "7876543210", "8765432109", "9876543210").forEach { phone ->
            assertNull(ContactInputValidator.validateContact(validName, phone, validRelationship))
        }
    }

    @Test
    fun `ten digit numbers not starting with 6 to 9 are rejected`() {
        listOf("1234567890", "0000000000", "5555555555").forEach { phone ->
            assertEquals(
                "Please enter a valid phone number.",
                ContactInputValidator.validateContact(validName, phone, validRelationship)
            )
        }
    }

    @Test
    fun `plus 91 international format is accepted for valid indian mobiles`() {
        assertNull(ContactInputValidator.validateContact(validName, "+919876543210", validRelationship))
        assertNull(ContactInputValidator.validateContact(validName, "+91 9876543210", validRelationship))
        assertNull(ContactInputValidator.validateContact(validName, "(+91) 9876543210", validRelationship))
    }

    @Test
    fun `plus 91 format with an invalid local number is rejected`() {
        assertEquals(
            "Please enter a valid phone number.",
            ContactInputValidator.validateContact(validName, "+911234567890", validRelationship)
        )
    }

    @Test
    fun `formatted indian numbers are accepted`() {
        assertNull(ContactInputValidator.validateContact(validName, "98765 43210", validRelationship))
        assertNull(ContactInputValidator.validateContact(validName, "98765-43210", validRelationship))
    }

    // ---- Relationship ----

    @Test
    fun `blank relationship is rejected`() {
        assertEquals(
            "Please enter a valid relationship.",
            ContactInputValidator.validateContact(validName, validPhone, "")
        )
        assertEquals(
            "Please enter a valid relationship.",
            ContactInputValidator.validateContact(validName, validPhone, "   ")
        )
    }

    @Test
    fun `numeric-only or symbol-only relationship is rejected`() {
        assertEquals(
            "Please enter a valid relationship.",
            ContactInputValidator.validateContact(validName, validPhone, "000")
        )
        assertEquals(
            "Please enter a valid relationship.",
            ContactInputValidator.validateContact(validName, validPhone, "!!!")
        )
    }

    @Test
    fun `normal relationship values are accepted`() {
        listOf("Mother", "Father", "Brother", "Sister", "Friend", "Guardian").forEach { relationship ->
            assertNull(ContactInputValidator.validateContact(validName, validPhone, relationship))
        }
    }

    // ---- Whole form ----

    @Test
    fun `a fully valid contact is accepted`() {
        assertNull(ContactInputValidator.validateContact("John Doe", "9876543210", "Friend"))
    }
}

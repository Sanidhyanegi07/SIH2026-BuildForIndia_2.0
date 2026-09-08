package com.example.georescux.core.validation

/**
 * Input validation for the Emergency Contacts screen.
 * Returns an error message to show, or null when the input is acceptable.
 * Pure Kotlin (no Android classes) so it can be unit tested, following the
 * same pattern as [AuthInputValidator].
 */
object ContactInputValidator {

    // Indian mobile number, after removing common formatting characters:
    // exactly 10 digits starting with 6-9, optionally prefixed by "+91".
    private val INDIAN_MOBILE_REGEX = Regex("^(\\+91)?[6-9][0-9]{9}$")

    // Formatting characters that are allowed inside a phone number.
    private val PHONE_FORMATTING = Regex("[\\s\\-().]")

    fun validateContact(name: String, phoneNumber: String, relationship: String): String? {
        if (name.isBlank() || !name.any { it.isLetter() }) {
            return "Please enter a valid name (must contain letters)."
        }

        val normalized = phoneNumber.replace(PHONE_FORMATTING, "")
        if (!INDIAN_MOBILE_REGEX.matches(normalized)) {
            return "Please enter a valid phone number."
        }

        if (relationship.isBlank() || !relationship.any { it.isLetter() }) {
            return "Please enter a valid relationship."
        }

        return null
    }
}

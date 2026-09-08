package com.example.georescux.domain.contacts

/**
 * A person who should be reached during an SOS emergency.
 * [priority] follows the "at most one PRIMARY" rule enforced by
 * [ContactRules] in the repository write path.
 */
data class EmergencyContact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val relationship: String,
    val priority: ContactPriority,
)

enum class ContactPriority {
    PRIMARY,
    SECONDARY,
}

package com.example.georescux.domain.contacts

/**
 * Pure rules for the emergency-contacts list. No Android, Firebase,
 * SharedPreferences, or org.json code here — every rule can be unit tested.
 */
object ContactRules {

    /**
     * Enforces "at most one PRIMARY contact":
     * - [updated] replaces any existing contact with the same id (or is appended).
     * - if [updated] is PRIMARY, every OTHER primary contact is demoted to
     *   SECONDARY automatically.
     * - zero PRIMARY contacts is a valid state (e.g. after deletion).
     */
    fun enforceSinglePrimary(contacts: List<EmergencyContact>, updated: EmergencyContact): List<EmergencyContact> {
        val demoted = if (updated.priority == ContactPriority.PRIMARY) {
            contacts.map { contact ->
                if (contact.id != updated.id && contact.priority == ContactPriority.PRIMARY) {
                    contact.copy(priority = ContactPriority.SECONDARY)
                } else {
                    contact
                }
            }
        } else {
            contacts
        }

        val index = demoted.indexOfFirst { it.id == updated.id }
        return if (index >= 0) {
            demoted.toMutableList().also { it[index] = updated }
        } else {
            demoted + updated
        }
    }
}

package com.example.georescux.data.sync

/**
 * Persistent, UID-scoped record of what still needs to reach Firebase
 * (Stage 7B-3, Step 1 foundation). Consumed by the sync repositories in
 * later 7B-3 steps; the store itself performs no network operations.
 *
 * Canonical keys (both implementations):
 *  - Contacts: "pending_contacts_backup_{uid}"   (Boolean)
 *  - SOS:      "pending_sos_alerts_{uid}"        (Set<String> of alertIds)
 *
 * Semantics:
 *  - Missing state means "nothing pending" (Contacts: false, SOS: empty set).
 *  - State survives process death (SharedPreferences-backed implementation).
 *  - Clearing an absent entry is a safe no-op.
 *  - Different UIDs never share state.
 */
interface SyncStateStore {
    fun isContactBackupPending(uid: String): Boolean

    fun setContactBackupPending(uid: String, pending: Boolean)

    fun pendingSosAlertIds(uid: String): Set<String>

    fun setSosAlertPending(uid: String, alertId: String, pending: Boolean)

    fun clearSosAlertPending(uid: String, alertId: String)
}

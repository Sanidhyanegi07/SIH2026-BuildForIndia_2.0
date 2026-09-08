package com.example.georescux.data.sync

import android.content.Context

/**
 * SharedPreferences-backed [SyncStateStore] using a dedicated
 * application-private "sync_state" file. State survives process death.
 *
 * SOS pending state is stored as a StringSet (copied on every access —
 * Android returns read-only sets). Keys are UID-scoped, so different
 * accounts never share pending state.
 */
class SharedPreferencesSyncStateStore(context: Context) : SyncStateStore {

    private val prefs = context.getSharedPreferences("sync_state", Context.MODE_PRIVATE)

    override fun isContactBackupPending(uid: String): Boolean =
        prefs.getBoolean(contactBackupKey(uid), false)

    override fun setContactBackupPending(uid: String, pending: Boolean) {
        prefs.edit().putBoolean(contactBackupKey(uid), pending).apply()
    }

    override fun pendingSosAlertIds(uid: String): Set<String> =
        prefs.getStringSet(sosPendingKey(uid), emptySet())?.toSet() ?: emptySet()

    override fun setSosAlertPending(uid: String, alertId: String, pending: Boolean) {
        val current = pendingSosAlertIds(uid).toMutableSet()
        if (pending) current.add(alertId) else current.remove(alertId)
        if (current.isEmpty()) {
            // Store nothing when the set empties out (mirrors the fake's
            // remove-when-empty semantics and keeps the prefs file clean).
            prefs.edit().remove(sosPendingKey(uid)).apply()
        } else {
            prefs.edit().putStringSet(sosPendingKey(uid), current).apply()
        }
    }

    override fun clearSosAlertPending(uid: String, alertId: String) {
        val current = pendingSosAlertIds(uid)
        if (alertId !in current) return
        val remaining = current - alertId
        val editor = prefs.edit()
        if (remaining.isEmpty()) {
            editor.remove(sosPendingKey(uid))
        } else {
            editor.putStringSet(sosPendingKey(uid), remaining)
        }
        editor.apply()
    }

    private fun contactBackupKey(uid: String) = "pending_contacts_backup_$uid"

    private fun sosPendingKey(uid: String) = "pending_sos_alerts_$uid"
}

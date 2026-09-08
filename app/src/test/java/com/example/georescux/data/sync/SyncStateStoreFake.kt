package com.example.georescux.data.sync

/**
 * In-memory [SyncStateStore] for JVM tests. Optionally shares a backing
 * map between store instances to simulate persistence across
 * "recreations" (a fresh store over the same backing map sees the same
 * state, like SharedPreferences does).
 */
class SyncStateStoreFake(
    private val backingStorage: MutableMap<String, Any> = mutableMapOf(),
) : SyncStateStore {

    override fun isContactBackupPending(uid: String): Boolean =
        backingStorage[contactBackupKey(uid)] as? Boolean ?: false

    override fun setContactBackupPending(uid: String, pending: Boolean) {
        if (pending) backingStorage[contactBackupKey(uid)] = true
        else backingStorage.remove(contactBackupKey(uid))
    }

    override fun pendingSosAlertIds(uid: String): Set<String> {
        val raw = backingStorage[sosPendingKey(uid)] as? Set<*> ?: return emptySet()
        return raw.filterIsInstance<String>().toSet()
    }

    override fun setSosAlertPending(uid: String, alertId: String, pending: Boolean) {
        val current = pendingSosAlertIds(uid).toMutableSet()
        if (pending) current.add(alertId) else current.remove(alertId)
        if (current.isEmpty()) backingStorage.remove(sosPendingKey(uid))
        else backingStorage[sosPendingKey(uid)] = current
    }

    override fun clearSosAlertPending(uid: String, alertId: String) {
        val current = pendingSosAlertIds(uid).toMutableSet()
        if (current.remove(alertId)) {
            if (current.isEmpty()) backingStorage.remove(sosPendingKey(uid))
            else backingStorage[sosPendingKey(uid)] = current
        }
    }

    private fun contactBackupKey(uid: String) = "pending_contacts_backup_$uid"

    private fun sosPendingKey(uid: String) = "pending_sos_alerts_$uid"
}

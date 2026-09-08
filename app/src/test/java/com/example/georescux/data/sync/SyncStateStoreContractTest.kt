package com.example.georescux.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the [SyncStateStore] contract against the in-memory fake:
 * UID-scoped Contacts pending flag, per-UID SOS pending alert sets,
 * persistence across store recreation, and idempotent clearing.
 */
class SyncStateStoreContractTest {

    private fun freshStore(backing: MutableMap<String, Any> = mutableMapOf()) =
        SyncStateStoreFake(backing)

    // ---- Contacts pending flag ----

    @Test
    fun `default contacts state is not pending`() {
        assertFalse(freshStore().isContactBackupPending("user-a"))
    }

    @Test
    fun `contacts pending flag can be set`() {
        val store = freshStore()
        store.setContactBackupPending("user-a", pending = true)
        assertTrue(store.isContactBackupPending("user-a"))
    }

    @Test
    fun `contacts pending flag can be cleared`() {
        val store = freshStore()
        store.setContactBackupPending("user-a", pending = true)
        store.setContactBackupPending("user-a", pending = false)
        assertFalse(store.isContactBackupPending("user-a"))
    }

    @Test
    fun `contacts state is isolated by uid`() {
        val store = freshStore()
        store.setContactBackupPending("user-a", pending = true)

        assertFalse(store.isContactBackupPending("user-b"))
    }

    // ---- SOS pending alert ids ----

    @Test
    fun `default sos pending set is empty`() {
        assertTrue(freshStore().pendingSosAlertIds("user-a").isEmpty())
    }

    @Test
    fun `sos alert can be marked pending`() {
        val store = freshStore()
        store.setSosAlertPending("user-a", alertId = "alert-1", pending = true)

        assertEquals(setOf("alert-1"), store.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `multiple sos alerts can be pending simultaneously`() {
        val store = freshStore()
        store.setSosAlertPending("user-a", alertId = "alert-1", pending = true)
        store.setSosAlertPending("user-a", alertId = "alert-2", pending = true)

        assertEquals(setOf("alert-1", "alert-2"), store.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `one sos alert can be cleared without clearing others`() {
        val store = freshStore()
        store.setSosAlertPending("user-a", alertId = "alert-1", pending = true)
        store.setSosAlertPending("user-a", alertId = "alert-2", pending = true)

        store.setSosAlertPending("user-a", alertId = "alert-1", pending = false)

        assertEquals(setOf("alert-2"), store.pendingSosAlertIds("user-a"))
    }

    @Test
    fun `sos state is isolated by uid`() {
        val store = freshStore()
        store.setSosAlertPending("user-a", alertId = "alert-1", pending = true)

        assertTrue(store.pendingSosAlertIds("user-b").isEmpty())
    }

    // ---- Persistence across store recreation ----

    @Test
    fun `state persists when the store is recreated over the same backing storage`() {
        val backing = mutableMapOf<String, Any>()
        val first = SyncStateStoreFake(backing)
        first.setContactBackupPending("user-a", pending = true)
        first.setSosAlertPending("user-a", alertId = "alert-1", pending = true)

        val recreated = SyncStateStoreFake(backing)
        assertTrue(recreated.isContactBackupPending("user-a"))
        assertEquals(setOf("alert-1"), recreated.pendingSosAlertIds("user-a"))
    }

    // ---- Idempotent clearing ----

    @Test
    fun `clearing already-clear state is safe and idempotent`() {
        val store = freshStore()

        store.setContactBackupPending("user-a", pending = false)
        store.setSosAlertPending("user-a", alertId = "alert-1", pending = false)
        store.setSosAlertPending("user-a", alertId = "alert-1", pending = false)

        assertFalse(store.isContactBackupPending("user-a"))
        assertTrue(store.pendingSosAlertIds("user-a").isEmpty())
    }

    @Test
    fun `clearing the last pending sos alert removes the sos state entirely`() {
        val store = freshStore()
        store.setSosAlertPending("user-a", alertId = "alert-1", pending = true)

        store.setSosAlertPending("user-a", alertId = "alert-1", pending = false)

        assertTrue(store.pendingSosAlertIds("user-a").isEmpty())
    }
}

package com.example.georescux.data.alerts

import android.content.Context
import com.example.georescux.domain.alerts.AdminAlert
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Receiving side of administrative alerts (spec §34).
 *
 * Administrators publish scoped alerts through Firebase (India, a state, or
 * a district). This observer picks up the ones that target the device's
 * active region, persists them locally so they stay readable offline, and
 * exposes them as a flow for the Alerts screen.
 *
 * The scoping is applied on the receiving device so a district-specific
 * alert is not shown to users it was not meant for. Publishing alerts is a
 * separate admin-portal capability.
 */
class AdminAlertObserver(
    context: Context,
    private val activeRegionId: () -> String,
) {

    private val store = AdminAlertStore(context)
    private val _alerts = MutableStateFlow(store.loadAll())
    val alerts: StateFlow<List<AdminAlert>> = _alerts.asStateFlow()

    private var listener: ValueEventListener? = null

    fun start() {
        if (listener != null) return
        val reference = FirebaseDatabase.getInstance().getReference(NODE)
        val newListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val received = snapshot.children.mapNotNull { child ->
                    runCatching { toAdminAlert(child) }.getOrNull()
                }
                val relevant = received.filter { it.targets(activeRegionId()) }
                // Persist only what targets this device, then publish.
                store.addAll(relevant)
                _alerts.value = store.loadAll().sortedByDescending { it.timestampMs }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        reference.addValueEventListener(newListener)
        listener = newListener
    }

    fun stop() {
        listener?.let { FirebaseDatabase.getInstance().getReference(NODE).removeEventListener(it) }
        listener = null
    }

    private fun toAdminAlert(child: DataSnapshot): AdminAlert? {
        val id = child.key ?: return null
        val title = child.child("title").getValue(String::class.java) ?: return null
        return AdminAlert(
            id = id,
            title = title,
            body = child.child("body").getValue(String::class.java).orEmpty(),
            timestampMs = child.child("timestampMs").getValue(Long::class.java)
                ?: System.currentTimeMillis(),
            scopeType = child.child("scopeType").getValue(String::class.java)
                ?: AdminAlert.SCOPE_INDIA,
            regionId = child.child("regionId").getValue(String::class.java),
            district = child.child("district").getValue(String::class.java),
        )
    }

    private companion object {
        const val NODE = "admin_alerts"
    }
}

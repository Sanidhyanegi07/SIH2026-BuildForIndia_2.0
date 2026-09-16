package com.example.georescux.domain.alerts

/**
 * One row of the unified Alerts screen (spec §20).
 *
 * Alerts is the single emergency information centre, so every kind of
 * entry — an SOS the user raised, an SOS received over the local BLE mesh,
 * or an official administrative alert — is normalised into this shape with
 * its source labelled. The raw records stay untouched in their own stores;
 * this is a read-only presentation model.
 */
data class AlertEntry(
    val id: String,
    val source: AlertSource,
    val timestampMs: Long,
    val title: String,
    val body: String,
    val note: String?,
    val locationLabel: String?,
    val durationLabel: String?,
    val isActive: Boolean,
)

/**
 * Where an alert came from. The label is shown as a badge so the user can
 * tell a nearby BLE SOS from an official notice at a glance.
 */
enum class AlertSource(val label: String, val isActiveEmergency: Boolean) {
    OWN_SOS("My SOS", true),
    BLE_SOS("BLE SOS", true),
    ADMIN("Administrative Alert", false),
}

/**
 * An official alert published by an administrator for a scope (India, a
 * state, or a district). Only the fields the receiving device needs.
 */
data class AdminAlert(
    val id: String,
    val title: String,
    val body: String,
    val timestampMs: Long,
    /** "india", "state" or "district" — used to decide delivery scope. */
    val scopeType: String,
    /** Region id when scoped to a state; null for India-wide. */
    val regionId: String?,
    /** Optional district name when scoped to a district. */
    val district: String? = null,
) {
    /** True when this alert should be delivered to the given active region. */
    fun targets(activeRegionId: String): Boolean = when (scopeType) {
        SCOPE_INDIA -> true
        SCOPE_STATE -> regionId != null && regionId == activeRegionId
        else -> regionId != null && regionId == activeRegionId
    }

    companion object {
        const val SCOPE_INDIA = "india"
        const val SCOPE_STATE = "state"
        const val SCOPE_DISTRICT = "district"
    }
}

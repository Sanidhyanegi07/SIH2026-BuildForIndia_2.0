package com.example.georescux.domain.alerts

import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocationStatus

/**
 * Turns SOS records and administrative alerts into the unified [AlertEntry]
 * rows the Alerts screen renders (spec §20).
 *
 * Pure and framework-free on purpose: the classification (was this SOS
 * received over BLE, or did I raise it?) and the formatting belong in one
 * testable place rather than scattered in the activity.
 */
object AlertEntryMerger {

    private const val BLE_PREFIX = "MESH_BLE_"
    private const val MESH_PREFIX = "MESH_"

    fun fromSosEmergency(emergency: SosEmergency): AlertEntry {
        val source = when {
            emergency.id.startsWith(BLE_PREFIX) -> AlertSource.BLE_SOS
            emergency.id.startsWith(MESH_PREFIX) -> AlertSource.BLE_SOS
            else -> AlertSource.OWN_SOS
        }
        return AlertEntry(
            id = emergency.id,
            source = source,
            timestampMs = emergency.startedAtMs,
            title = if (source == AlertSource.BLE_SOS) {
                "Nearby GeoRescuX SOS"
            } else {
                if (emergency.isActive) "Emergency in progress" else "My emergency"
            },
            body = emergency.note.orEmpty(),
            note = emergency.note,
            locationLabel = formatLocation(emergency),
            durationLabel = formatDuration(emergency),
            isActive = emergency.isActive,
        )
    }

    fun fromAdminAlert(alert: AdminAlert): AlertEntry = AlertEntry(
        id = alert.id,
        source = AlertSource.ADMIN,
        timestampMs = alert.timestampMs,
        title = alert.title,
        body = alert.body,
        note = null,
        locationLabel = alert.district,
        durationLabel = alert.scopeLabel(),
        isActive = false,
    )

    /** Newest first, so a live SOS always sits at the top of the list. */
    fun merge(
        sosHistory: List<SosEmergency>,
        adminAlerts: List<AdminAlert>,
    ): List<AlertEntry> {
        val entries = sosHistory.map { fromSosEmergency(it) } +
            adminAlerts.map { fromAdminAlert(it) }
        return entries.sortedWith(
            compareByDescending<AlertEntry> { it.isActive }
                .thenByDescending { it.timestampMs },
        )
    }

    private fun AdminAlert.scopeLabel(): String = when (scopeType) {
        AdminAlert.SCOPE_INDIA -> "India-wide"
        AdminAlert.SCOPE_STATE -> regionId.orEmpty()
        AdminAlert.SCOPE_DISTRICT -> district.orEmpty()
        else -> regionId.orEmpty()
    }

    private fun formatDuration(emergency: SosEmergency): String {
        val stopped = emergency.stoppedAtMs
            ?: return "ongoing"
        val seconds = ((stopped - emergency.startedAtMs) / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        val remainder = seconds % 60
        return when {
            minutes == 0L -> "${remainder}s"
            remainder == 0L -> "${minutes}m"
            else -> "${minutes}m ${remainder}s"
        }
    }

    private fun formatLocation(emergency: SosEmergency): String? {
        val location = emergency.location
            ?: return when (emergency.locationStatus) {
                SosLocationStatus.PERMISSION_MISSING -> "Location permission needed"
                SosLocationStatus.UNAVAILABLE -> "Location unavailable"
                else -> "No location recorded"
            }
        return "📍 %.4f, %.4f".format(location.latitude, location.longitude)
    }
}

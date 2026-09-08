package com.example.georescux.data.sync

import com.example.georescux.domain.sos.SosEmergency

/**
 * Cloud storage for SOS emergency records. Implemented with Firebase
 * Realtime Database; fakes replace it in JVM tests.
 */
interface SosCloudDataSource {
    /**
     * Upserts one emergency record under sos_alerts/{uid}/{alertId}.
     * Updating an existing alert updates the same node — never a duplicate.
     * Returns true on success; false on any failure — never throws.
     */
    suspend fun upsertEmergency(emergency: SosEmergency): Boolean

    /**
     * Upserts the complete local history in one write (an empty list
     * clears the node). Returns true on success; false on any failure.
     */
    suspend fun upsertHistory(emergencies: List<SosEmergency>): Boolean
}

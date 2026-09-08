package com.example.georescux.data.sos

import com.example.georescux.domain.sos.SosEmergency

/**
 * Local on-device storage for SOS records — deliberately not Firebase,
 * so Phase 1 works completely offline. One active emergency is kept,
 * plus a small history of finished emergencies.
 */
interface LocalSosStore {
    fun loadActive(): SosEmergency?
    fun saveActive(emergency: SosEmergency)
    fun clearActive()
    fun loadHistory(): List<SosEmergency>
    fun addToHistory(emergency: SosEmergency)
}

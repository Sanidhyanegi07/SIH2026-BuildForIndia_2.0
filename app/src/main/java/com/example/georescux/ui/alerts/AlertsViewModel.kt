package com.example.georescux.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.domain.alerts.AdminAlert
import com.example.georescux.domain.alerts.AlertEntry
import com.example.georescux.domain.alerts.AlertEntryMerger
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.repository.SosRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only view of the emergency information centre (spec §20).
 *
 * Keeps the original SOS-history surface ([alerts] / [latestAlert]) intact and
 * adds [allAlerts]: the merged, source-labelled feed of every alert type —
 * the user's own SOS events, SOS alerts received over the local BLE mesh,
 * and official administrative alerts for the active region.
 *
 * This ViewModel never creates, modifies, or deletes records — it only reads
 * [SosRepository.getHistory] and the administrative alert source.
 */
class AlertsViewModel(
    sosRepository: SosRepository,
    adminAlerts: List<AdminAlert> = emptyList(),
) : ViewModel() {

    private val history: List<SosEmergency> = sosRepository.getHistory()

    private val _alerts = MutableStateFlow(history)
    val alerts: StateFlow<List<SosEmergency>> = _alerts.asStateFlow()

    /** The newest completed emergency, or null when there is none. */
    private val _latestAlert = MutableStateFlow(history.firstOrNull())
    val latestAlert: StateFlow<SosEmergency?> = _latestAlert.asStateFlow()

    private val _allAlerts = MutableStateFlow(AlertEntryMerger.merge(history, adminAlerts))
    val allAlerts: StateFlow<List<AlertEntry>> = _allAlerts.asStateFlow()

    /** Pushes live administrative alerts into the merged feed. */
    fun setAdminAlerts(alerts: List<AdminAlert>) {
        _allAlerts.value = AlertEntryMerger.merge(history, alerts)
    }

    class Factory(
        private val sosRepository: SosRepository,
        private val adminAlerts: List<AdminAlert> = emptyList(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AlertsViewModel(sosRepository, adminAlerts) as T
        }
    }
}

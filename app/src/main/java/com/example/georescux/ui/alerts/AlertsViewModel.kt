package com.example.georescux.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.repository.SosRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only view of the SOS emergency history (newest first).
 * This ViewModel never creates, modifies, or deletes SOS records —
 * it only reads [SosRepository.getHistory].
 */
class AlertsViewModel(sosRepository: SosRepository) : ViewModel() {

    private val history: List<SosEmergency> = sosRepository.getHistory()

    private val _alerts = MutableStateFlow(history)
    val alerts: StateFlow<List<SosEmergency>> = _alerts.asStateFlow()

    /** The newest completed emergency, or null when there is none. */
    private val _latestAlert = MutableStateFlow(history.firstOrNull())
    val latestAlert: StateFlow<SosEmergency?> = _latestAlert.asStateFlow()

    class Factory(private val sosRepository: SosRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AlertsViewModel(sosRepository) as T
        }
    }
}

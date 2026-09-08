package com.example.georescux.ui.sos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.domain.repository.LocationRepository
import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.CancelSosUseCase
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosEvent
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.SosLocationPolicy
import com.example.georescux.domain.sos.SosLocationStatus
import com.example.georescux.domain.sos.SosState
import com.example.georescux.domain.sos.SosStateMachine
import com.example.georescux.domain.sos.StartSosUseCase
import com.example.georescux.domain.sos.StopSosUseCase
import com.example.georescux.domain.sos.UpdateSosLocationUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * What the SOS screen currently shows.
 */
data class SosUiState(
    val state: SosState = SosState.IDLE,
    val countdownSeconds: Int? = null,
    val emergency: SosEmergency? = null,
)

/**
 * Drives the SOS state machine for the countdown and live emergency
 * screens, including best-effort location acquisition (Phase 2):
 * acquisition starts when the emergency becomes ACTIVE, never blocks or
 * cancels it, and is stopped when the emergency ends or the ViewModel is
 * cleared. No background tracking.
 */
class SosViewModel(
    private val startSos: StartSosUseCase,
    private val cancelSos: CancelSosUseCase,
    private val stopSos: StopSosUseCase,
    private val sosRepository: SosRepository,
    private val updateSosLocation: UpdateSosLocationUseCase,
    private val locationRepository: LocationRepository,
    initialState: SosState,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SosUiState(state = initialState))
    val uiState: StateFlow<SosUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(mainDispatcher + SupervisorJob())
    private var countdownJob: Job? = null
    private var locationTimeoutJob: Job? = null
    private var acquisitionActive = false
    private var lastLocationPersistAtMs = 0L

    init {
        // When restoring an active emergency (e.g. after an app restart),
        // load its record so the screen can show the last known location.
        if (initialState == SosState.ACTIVE) {
            _uiState.value = _uiState.value.copy(emergency = sosRepository.getActiveEmergency())
        }
    }

    /** Starts the 5-second countdown. Only valid from the COUNTDOWN state. */
    fun beginCountdown() {
        if (_uiState.value.state != SosState.COUNTDOWN) return
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (seconds in COUNTDOWN_SECONDS downTo 1) {
                _uiState.value = _uiState.value.copy(countdownSeconds = seconds)
                delay(1000)
            }
            countdownFinished()
        }
    }

    /** Cancelled countdown: back to IDLE, and no SOS record may exist. */
    fun cancelCountdown() {
        countdownJob?.cancel()
        transition(SosEvent.COUNTDOWN_CANCELLED)
        cancelSos()
        _uiState.value = _uiState.value.copy(countdownSeconds = null)
    }

    /** Marks the active emergency completed locally and returns to IDLE. */
    fun stopEmergency() {
        transition(SosEvent.STOP_REQUESTED)          // ACTIVE -> STOPPING
        stopSos(System.currentTimeMillis())          // persist completion + history
        transition(SosEvent.STOP_COMPLETED)          // STOPPING -> COMPLETED
        transition(SosEvent.COMPLETED_ACKNOWLEDGED)  // COMPLETED -> IDLE
        stopLocationAcquisition()
    }

    // ---- Location (Phase 2) ----

    /**
     * Begins best-effort location acquisition. Called by the UI after the
     * permission check; never blocks or cancels the emergency.
     */
    fun beginLocationAcquisition() {
        if (acquisitionActive) return
        if (_uiState.value.state != SosState.ACTIVE) return
        acquisitionActive = true
        locationRepository.startAcquisition { fix ->
            scope.launch { onLocationFix(fix) }
        }
        locationTimeoutJob = scope.launch {
            delay(SosLocationPolicy.TIMEOUT_MS)
            onLocationTimeout()
        }
    }

    /** Called by the UI when the user denied the location permission. */
    fun markPermissionMissing() {
        val current = _uiState.value
        if (current.state != SosState.ACTIVE) return
        // Only while waiting for the first fix — never downgrade an acquired fix.
        if (!SosLocationPolicy.shouldMarkUnavailable(current.emergency?.locationStatus)) return
        persistLocationStatus(SosLocationStatus.PERMISSION_MISSING)
    }

    /** Called when no fix arrived within the timeout window. */
    fun onLocationTimeout() {
        val current = _uiState.value
        if (current.state != SosState.ACTIVE) return
        if (!SosLocationPolicy.shouldMarkUnavailable(current.emergency?.locationStatus)) return
        persistLocationStatus(SosLocationStatus.UNAVAILABLE)
    }

    private fun onLocationFix(fix: SosLocation) {
        val current = _uiState.value
        if (current.state != SosState.ACTIVE) return
        val now = System.currentTimeMillis()
        if (!SosLocationPolicy.shouldPersistFix(lastLocationPersistAtMs, now)) return
        lastLocationPersistAtMs = now
        locationTimeoutJob?.cancel() // a fix arrived; the timeout no longer applies
        updateSosLocation(fix, SosLocationStatus.ACQUIRED)
        _uiState.value = _uiState.value.copy(emergency = sosRepository.getActiveEmergency())
    }

    private fun persistLocationStatus(status: SosLocationStatus) {
        locationTimeoutJob?.cancel()
        updateSosLocation(null, status)
        _uiState.value = _uiState.value.copy(emergency = sosRepository.getActiveEmergency())
    }

    private fun stopLocationAcquisition() {
        locationTimeoutJob?.cancel()
        locationRepository.stopAcquisition()
        acquisitionActive = false
    }

    private fun countdownFinished() {
        transition(SosEvent.COUNTDOWN_FINISHED)      // COUNTDOWN -> ACTIVE
        // Persist locally (works offline). The duplicate guard means a retry
        // can never create a second emergency.
        val emergency = startSos(UUID.randomUUID().toString(), System.currentTimeMillis())
            ?: sosRepository.getActiveEmergency()
        _uiState.value = _uiState.value.copy(emergency = emergency)
    }

    private fun transition(event: SosEvent) {
        val current = _uiState.value
        _uiState.value = current.copy(state = SosStateMachine.onEvent(current.state, event))
    }

    override fun onCleared() {
        stopLocationAcquisition()
        scope.cancel()
        super.onCleared()
    }

    class Factory(
        private val startSos: StartSosUseCase,
        private val cancelSos: CancelSosUseCase,
        private val stopSos: StopSosUseCase,
        private val sosRepository: SosRepository,
        private val updateSosLocation: UpdateSosLocationUseCase,
        private val locationRepository: LocationRepository,
        private val initialState: SosState,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SosViewModel(
                startSos,
                cancelSos,
                stopSos,
                sosRepository,
                updateSosLocation,
                locationRepository,
                initialState,
            ) as T
        }
    }

    companion object {
        const val COUNTDOWN_SECONDS = 5
    }
}

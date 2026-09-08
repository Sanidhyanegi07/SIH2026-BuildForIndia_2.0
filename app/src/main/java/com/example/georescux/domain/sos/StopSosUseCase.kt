package com.example.georescux.domain.sos

import com.example.georescux.domain.repository.SosRepository

/**
 * Marks the active emergency completed and stores it in history.
 * Returns the completed record, or null when no emergency is active.
 */
class StopSosUseCase(private val sosRepository: SosRepository) {
    operator fun invoke(stoppedAtMs: Long): SosEmergency? =
        sosRepository.completeEmergency(stoppedAtMs)
}

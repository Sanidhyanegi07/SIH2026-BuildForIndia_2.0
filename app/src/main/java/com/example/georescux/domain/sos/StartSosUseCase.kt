package com.example.georescux.domain.sos

import com.example.georescux.domain.repository.SosRepository

/**
 * Activates an emergency locally (works offline).
 * Returns the created emergency, or null when one is already active —
 * there can never be two simultaneous SOS emergencies.
 */
class StartSosUseCase(private val sosRepository: SosRepository) {
    operator fun invoke(id: String, startedAtMs: Long): SosEmergency? =
        sosRepository.startEmergency(id, startedAtMs)
}

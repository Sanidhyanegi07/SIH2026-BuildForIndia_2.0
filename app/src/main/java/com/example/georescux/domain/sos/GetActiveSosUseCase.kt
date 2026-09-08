package com.example.georescux.domain.sos

import com.example.georescux.domain.repository.SosRepository

/**
 * Reads the currently active emergency, if any.
 * Used to restore the Active Emergency screen after an app restart.
 */
class GetActiveSosUseCase(private val sosRepository: SosRepository) {
    operator fun invoke(): SosEmergency? = sosRepository.getActiveEmergency()
}

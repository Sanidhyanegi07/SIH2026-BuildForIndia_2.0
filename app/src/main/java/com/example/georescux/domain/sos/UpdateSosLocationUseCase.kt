package com.example.georescux.domain.sos

import com.example.georescux.domain.repository.SosRepository

/**
 * Persists the latest location information of the currently active
 * emergency. Updating never creates a new emergency or a history record.
 */
class UpdateSosLocationUseCase(private val sosRepository: SosRepository) {
    operator fun invoke(location: SosLocation?, status: SosLocationStatus) {
        sosRepository.updateActiveEmergencyLocation(location, status)
    }
}

package com.example.georescux.domain.sos

import com.example.georescux.domain.repository.SosRepository

/**
 * Cancels a countdown. A cancelled countdown must leave no SOS record,
 * and indeed nothing was persisted during the countdown — this use case
 * exists to keep the UI layer free of that rule and to defend it.
 */
class CancelSosUseCase(private val sosRepository: SosRepository) {
    operator fun invoke() {
        check(sosRepository.getActiveEmergency() == null) {
            "Cannot cancel a countdown while an emergency is already active"
        }
    }
}

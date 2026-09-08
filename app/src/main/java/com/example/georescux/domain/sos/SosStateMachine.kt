package com.example.georescux.domain.sos

/**
 * Pure SOS state machine: maps (state, event) to the next state.
 * It contains no Android and no storage code, so every transition can be
 * unit tested. Illegal transitions throw on purpose — silently ignoring a
 * wrong state is never acceptable in an emergency feature.
 *
 * IDLE -> ARMING -> COUNTDOWN -> ACTIVE -> STOPPING -> COMPLETED -> IDLE
 * with exits: ARMING -> IDLE (early release), COUNTDOWN -> IDLE (cancel).
 */
object SosStateMachine {

    fun onEvent(state: SosState, event: SosEvent): SosState {
        return when (state to event) {
            SosState.IDLE to SosEvent.HOLD_STARTED -> SosState.ARMING
            SosState.ARMING to SosEvent.HOLD_RELEASED -> SosState.IDLE
            SosState.ARMING to SosEvent.HOLD_COMPLETED -> SosState.COUNTDOWN
            SosState.COUNTDOWN to SosEvent.COUNTDOWN_CANCELLED -> SosState.IDLE
            SosState.COUNTDOWN to SosEvent.COUNTDOWN_FINISHED -> SosState.ACTIVE
            SosState.ACTIVE to SosEvent.STOP_REQUESTED -> SosState.STOPPING
            SosState.STOPPING to SosEvent.STOP_COMPLETED -> SosState.COMPLETED
            SosState.COMPLETED to SosEvent.COMPLETED_ACKNOWLEDGED -> SosState.IDLE
            else -> throw IllegalStateException("Illegal SOS transition: $state --$event--> ?")
        }
    }
}

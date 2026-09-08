package com.example.georescux.domain.sos

/** Events that can happen while the SOS flow runs. */
enum class SosEvent {
    HOLD_STARTED,          // user pressed the SOS button
    HOLD_RELEASED,         // user released before 2 seconds (silent cancel)
    HOLD_COMPLETED,        // 2 seconds reached -> countdown begins
    COUNTDOWN_CANCELLED,   // user tapped anywhere during the countdown
    COUNTDOWN_FINISHED,    // countdown reached 0 -> emergency activates
    STOP_REQUESTED,        // user tapped "Stop Emergency"
    STOP_COMPLETED,        // completion was persisted locally
    COMPLETED_ACKNOWLEDGED,// UI has shown the completion -> back to IDLE
}

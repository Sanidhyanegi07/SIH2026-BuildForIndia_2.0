package com.example.georescux.domain.sos

/** States of the SOS emergency flow. */
enum class SosState {
    IDLE,
    ARMING,
    COUNTDOWN,
    ACTIVE,
    STOPPING,
    COMPLETED,
}

package com.example.georescux.domain.ble

/**
 * The explicit BLE subsystem states (diagnostic contract, one value per
 * observable phase of the connection flow).
 *
 * Top-level subsystem phases (scanning/advertising run concurrently):
 * [IDLE], [SCANNING], [ADVERTISING].
 *
 * Per-peer GATT connection flow (both roles):
 * [CONNECTING] -> [CONNECTED] -> [DISCOVERING_SERVICES] -> [READY]
 * -> [TRANSFERRING] -> [DISCONNECTING] -> [DISCONNECTED], plus [ERROR].
 */
enum class GeoRescueBleState {
    IDLE,
    SCANNING,
    ADVERTISING,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    READY,
    TRANSFERRING,
    DISCONNECTING,
    DISCONNECTED,
    ERROR,
}

/**
 * Pure connection-flow state machine for one GATT peer. Enforces the
 * documented flow so impossible jumps (e.g. IDLE -> READY) are caught in
 * tests instead of only in the field.
 *
 * Legal transitions:
 *   CONNECTING            -> CONNECTED, ERROR, DISCONNECTED
 *   CONNECTED             -> DISCOVERING_SERVICES, ERROR, DISCONNECTED
 *   DISCOVERING_SERVICES  -> READY, ERROR, DISCONNECTED
 *   READY                 -> TRANSFERRING, DISCONNECTING, DISCONNECTED, ERROR
 *   TRANSFERRING          -> READY, DISCONNECTING, DISCONNECTED, ERROR
 *   DISCONNECTING         -> DISCONNECTED
 *   DISCONNECTED          -> CONNECTING (reconnect), IDLE (forgotten)
 *   ERROR                 -> CONNECTING (retry), IDLE (forgotten)
 */
class GeoRescueBleConnectionStateMachine(initial: GeoRescueBleState = GeoRescueBleState.IDLE) {

    var state: GeoRescueBleState = initial
        private set

    private var lastTransitionError: String? = null

    /**
     * Attempts [next]. Returns true when the transition is legal; false
     * otherwise (state unchanged, reason recorded for diagnostics).
     */
    @Synchronized
    fun transitionTo(next: GeoRescueBleState): Boolean {
        if (next == state) return true
        val legal = next in allowedFrom(state)
        if (!legal) {
            lastTransitionError = "Illegal BLE state transition $state -> $next"
            return false
        }
        state = next
        return true
    }

    val lastError: String? get() = lastTransitionError

    fun reset(): GeoRescueBleConnectionStateMachine = apply { state = GeoRescueBleState.IDLE }

    companion object {
        private val allowed = mapOf(
            GeoRescueBleState.IDLE to setOf(
                GeoRescueBleState.CONNECTING, GeoRescueBleState.ERROR
            ),
            GeoRescueBleState.CONNECTING to setOf(
                GeoRescueBleState.CONNECTED, GeoRescueBleState.ERROR, GeoRescueBleState.DISCONNECTED
            ),
            GeoRescueBleState.CONNECTED to setOf(
                GeoRescueBleState.DISCOVERING_SERVICES, GeoRescueBleState.ERROR, GeoRescueBleState.DISCONNECTED
            ),
            GeoRescueBleState.DISCOVERING_SERVICES to setOf(
                GeoRescueBleState.READY, GeoRescueBleState.ERROR, GeoRescueBleState.DISCONNECTED
            ),
            GeoRescueBleState.READY to setOf(
                GeoRescueBleState.TRANSFERRING, GeoRescueBleState.DISCONNECTING,
                GeoRescueBleState.DISCONNECTED, GeoRescueBleState.ERROR
            ),
            GeoRescueBleState.TRANSFERRING to setOf(
                GeoRescueBleState.READY, GeoRescueBleState.DISCONNECTING,
                GeoRescueBleState.DISCONNECTED, GeoRescueBleState.ERROR
            ),
            GeoRescueBleState.DISCONNECTING to setOf(GeoRescueBleState.DISCONNECTED),
            GeoRescueBleState.DISCONNECTED to setOf(GeoRescueBleState.CONNECTING, GeoRescueBleState.IDLE),
            GeoRescueBleState.ERROR to setOf(GeoRescueBleState.CONNECTING, GeoRescueBleState.IDLE),
            GeoRescueBleState.SCANNING to setOf(GeoRescueBleState.CONNECTING),
            GeoRescueBleState.ADVERTISING to setOf(GeoRescueBleState.CONNECTING),
        )
    }

    private fun allowedFrom(current: GeoRescueBleState): Set<GeoRescueBleState> {
        val fromTable = allowed[current]
        if (fromTable != null) return fromTable
        // Self-contained roles (SCANNING/ADVERTISING) reach CONNECTING only;
        // everything else not in the table can go anywhere recovery allows.
        return setOf(GeoRescueBleState.CONNECTING, GeoRescueBleState.IDLE)
    }
}

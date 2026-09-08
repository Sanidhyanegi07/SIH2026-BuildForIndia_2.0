package com.example.georescux.domain.sos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies every legal SOS transition and the most important cancellation
 * cases of the state machine.
 */
class SosStateMachineTest {

    @Test
    fun `idle arms when hold starts`() {
        assertEquals(
            SosState.ARMING,
            SosStateMachine.onEvent(SosState.IDLE, SosEvent.HOLD_STARTED)
        )
    }

    @Test
    fun `early release during arming returns silently to idle`() {
        val state = SosStateMachine.onEvent(SosState.IDLE, SosEvent.HOLD_STARTED)
        assertEquals(SosState.IDLE, SosStateMachine.onEvent(state, SosEvent.HOLD_RELEASED))
    }

    @Test
    fun `completed hold moves from arming to countdown`() {
        val state = SosStateMachine.onEvent(SosState.IDLE, SosEvent.HOLD_STARTED)
        assertEquals(SosState.COUNTDOWN, SosStateMachine.onEvent(state, SosEvent.HOLD_COMPLETED))
    }

    @Test
    fun `cancelling the countdown returns to idle`() {
        val state = SosStateMachine.onEvent(SosState.COUNTDOWN, SosEvent.COUNTDOWN_CANCELLED)
        assertEquals(SosState.IDLE, state)
    }

    @Test
    fun `countdown reaching zero activates the emergency`() {
        val state = SosStateMachine.onEvent(SosState.COUNTDOWN, SosEvent.COUNTDOWN_FINISHED)
        assertEquals(SosState.ACTIVE, state)
    }

    @Test
    fun `stop request moves active to stopping`() {
        assertEquals(
            SosState.STOPPING,
            SosStateMachine.onEvent(SosState.ACTIVE, SosEvent.STOP_REQUESTED)
        )
    }

    @Test
    fun `stopping completes and acknowledging returns to idle`() {
        val completed = SosStateMachine.onEvent(SosState.STOPPING, SosEvent.STOP_COMPLETED)
        assertEquals(SosState.COMPLETED, completed)
        assertEquals(
            SosState.IDLE,
            SosStateMachine.onEvent(completed, SosEvent.COMPLETED_ACKNOWLEDGED)
        )
    }

    @Test
    fun `full happy path from idle back to idle`() {
        var state = SosState.IDLE
        val events = listOf(
            SosEvent.HOLD_STARTED,
            SosEvent.HOLD_COMPLETED,
            SosEvent.COUNTDOWN_FINISHED,
            SosEvent.STOP_REQUESTED,
            SosEvent.STOP_COMPLETED,
            SosEvent.COMPLETED_ACKNOWLEDGED,
        )
        for (event in events) {
            state = SosStateMachine.onEvent(state, event)
        }
        assertEquals(SosState.IDLE, state)
    }

    @Test(expected = IllegalStateException::class)
    fun `a plain tap cannot activate sos`() {
        // A normal tap must never reach the countdown or activate an emergency.
        SosStateMachine.onEvent(SosState.IDLE, SosEvent.COUNTDOWN_FINISHED)
    }

    @Test(expected = IllegalStateException::class)
    fun `arming cannot be started while a countdown runs`() {
        SosStateMachine.onEvent(SosState.COUNTDOWN, SosEvent.HOLD_STARTED)
    }

    @Test(expected = IllegalStateException::class)
    fun `an active emergency cannot be re-activated`() {
        SosStateMachine.onEvent(SosState.ACTIVE, SosEvent.COUNTDOWN_FINISHED)
    }
}

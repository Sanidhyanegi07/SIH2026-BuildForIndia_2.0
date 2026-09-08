package com.example.georescux.domain.sos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure SOS location rules: first fix persists immediately,
 * later fixes are throttled, and "unavailable" only applies while the
 * first fix is still missing.
 */
class SosLocationPolicyTest {

    @Test
    fun `the first fix always persists`() {
        assertTrue(SosLocationPolicy.shouldPersistFix(lastPersistAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun `a fix within 60 seconds of the last one is throttled`() {
        val lastPersist = 10_000L
        assertFalse(
            SosLocationPolicy.shouldPersistFix(
                lastPersistAtMs = lastPersist,
                nowMs = lastPersist + SosLocationPolicy.THROTTLE_MS - 1,
            )
        )
    }

    @Test
    fun `a fix after 60 seconds persists`() {
        val lastPersist = 10_000L
        assertTrue(
            SosLocationPolicy.shouldPersistFix(
                lastPersistAtMs = lastPersist,
                nowMs = lastPersist + SosLocationPolicy.THROTTLE_MS,
            )
        )
    }

    @Test
    fun `unavailable applies only while waiting for the first fix`() {
        assertTrue(SosLocationPolicy.shouldMarkUnavailable(currentStatus = null))
        assertFalse(SosLocationPolicy.shouldMarkUnavailable(currentStatus = SosLocationStatus.ACQUIRED))
        assertFalse(SosLocationPolicy.shouldMarkUnavailable(currentStatus = SosLocationStatus.UNAVAILABLE))
        assertFalse(
            SosLocationPolicy.shouldMarkUnavailable(currentStatus = SosLocationStatus.PERMISSION_MISSING)
        )
    }
}

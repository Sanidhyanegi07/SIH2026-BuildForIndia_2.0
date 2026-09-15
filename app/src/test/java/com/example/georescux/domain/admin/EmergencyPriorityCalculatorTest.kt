package com.example.georescux.domain.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class EmergencyPriorityCalculatorTest {

    private data class TestEmergency(override val isVerified: Boolean) : EmergencyVerifiable

    @Test
    fun `calculates stats correctly for mixed dataset`() {
        val dataset = listOf(
            TestEmergency(isVerified = true),
            TestEmergency(isVerified = true),
            TestEmergency(isVerified = false),
            TestEmergency(isVerified = false),
            TestEmergency(isVerified = false)
        )

        val stats = EmergencyPriorityCalculator.calculate(dataset)

        // Raw count (§13.3 item 35)
        assertEquals(5, stats.totalSosCount)

        // Verified count
        assertEquals(2, stats.verifiedUserSosCount)

        // Unverified count
        assertEquals(3, stats.unverifiedUserSosCount)

        // Priority Score formula: (verified * 2) + (unverified * 1) (§13.3 item 37)
        // (2 * 2) + (3 * 1) = 7
        assertEquals(7, stats.emergencyPriorityScore)
    }

    @Test
    fun `calculates stats correctly for empty dataset`() {
        val dataset = emptyList<TestEmergency>()

        val stats = EmergencyPriorityCalculator.calculate(dataset)

        assertEquals(0, stats.totalSosCount)
        assertEquals(0, stats.verifiedUserSosCount)
        assertEquals(0, stats.unverifiedUserSosCount)
        assertEquals(0, stats.emergencyPriorityScore)
    }

    @Test
    fun `calculates stats correctly for only verified dataset`() {
        val dataset = listOf(
            TestEmergency(isVerified = true),
            TestEmergency(isVerified = true)
        )

        val stats = EmergencyPriorityCalculator.calculate(dataset)

        assertEquals(2, stats.totalSosCount)
        assertEquals(2, stats.verifiedUserSosCount)
        assertEquals(0, stats.unverifiedUserSosCount)
        assertEquals(4, stats.emergencyPriorityScore)
    }

    @Test
    fun `calculates stats correctly for only unverified dataset`() {
        val dataset = listOf(
            TestEmergency(isVerified = false)
        )

        val stats = EmergencyPriorityCalculator.calculate(dataset)

        assertEquals(1, stats.totalSosCount)
        assertEquals(0, stats.verifiedUserSosCount)
        assertEquals(1, stats.unverifiedUserSosCount)
        assertEquals(1, stats.emergencyPriorityScore)
    }
}

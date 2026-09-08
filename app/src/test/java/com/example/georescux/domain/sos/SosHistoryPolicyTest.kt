package com.example.georescux.domain.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the unbounded-history cap: newest kept, oldest dropped, order preserved. */
class SosHistoryPolicyTest {

    private fun emergency(id: String, startedAtMs: Long) = SosEmergency(id = id, startedAtMs = startedAtMs)

    @Test
    fun `keeps only the newest cap entries`() {
        val emergencies = (1..60L).map { emergency("e$it", startedAtMs = it * 1_000L) }

        val kept = SosHistoryPolicy.keepNewest(emergencies, cap = 50)

        assertEquals(50, kept.size)
        assertTrue(kept.none { it.id == "e1" }) // oldest dropped
        assertTrue(kept.any { it.id == "e60" }) // newest kept
    }

    @Test
    fun `returns newest first regardless of input order`() {
        val kept = SosHistoryPolicy.keepNewest(
            listOf(emergency("old", 1_000L), emergency("new", 9_000L), emergency("mid", 5_000L)),
            cap = 3,
        )
        assertEquals(listOf("new", "mid", "old"), kept.map { it.id })
    }

    @Test
    fun `a cap of zero keeps nothing and short lists are returned whole`() {
        assertTrue(SosHistoryPolicy.keepNewest(listOf(emergency("a", 1L)), cap = 0).isEmpty())
        assertEquals(2, SosHistoryPolicy.keepNewest(listOf(emergency("a", 1L), emergency("b", 2L))).size)
    }
}

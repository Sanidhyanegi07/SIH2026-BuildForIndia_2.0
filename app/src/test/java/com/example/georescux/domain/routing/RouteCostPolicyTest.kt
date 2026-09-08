package com.example.georescux.domain.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the routing cost model: distance-based base cost, additive
 * hazard penalties (undirected), and blocked edges overriding everything.
 */
class RouteCostPolicyTest {

    private val edge = RouteEdge("a", "b", distanceMeters = 150.0)

    @Test
    fun `normal cost equals the edge distance`() {
        assertEquals(150.0, RouteCostPolicy.edgeCostMeters(edge, emptyList()))
    }

    @Test
    fun `a hazard on the edge adds its penalty`() {
        val hazard = RoadHazard("hz-1", "a", "b", penaltyMeters = 100.0)

        assertEquals(250.0, RouteCostPolicy.edgeCostMeters(edge, listOf(hazard)))
    }

    @Test
    fun `hazards on other edges do not affect the cost`() {
        val hazard = RoadHazard("hz-2", "c", "d", penaltyMeters = 100.0)

        assertEquals(150.0, RouteCostPolicy.edgeCostMeters(edge, listOf(hazard)))
    }

    @Test
    fun `the hazard applies to the undirected pair in either direction`() {
        val hazard = RoadHazard("hz-1", "b", "a", penaltyMeters = 100.0)

        assertEquals(250.0, RouteCostPolicy.edgeCostMeters(edge, listOf(hazard)))
    }

    @Test
    fun `blocked edge is excluded`() {
        val blocked = edge.copy(blocked = true)

        assertNull(RouteCostPolicy.edgeCostMeters(blocked, emptyList()))
    }

    @Test
    fun `blocked overrides hazards`() {
        val hazard = RoadHazard("hz-1", "a", "b", penaltyMeters = 100.0)
        val blocked = edge.copy(blocked = true)

        assertNull(RouteCostPolicy.edgeCostMeters(blocked, listOf(hazard)))
    }
}

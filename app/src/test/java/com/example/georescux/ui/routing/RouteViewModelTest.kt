package com.example.georescux.ui.routing

import com.example.georescux.domain.repository.RouteRepository
import com.example.georescux.domain.routing.RouteEdge
import com.example.georescux.domain.routing.RouteGraph
import com.example.georescux.domain.routing.RouteNode
import com.example.georescux.domain.routing.SafeHavenFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the routing ViewModel: route exposure, not-found state, reroute
 * recomputation and safe handling of unknown nodes — all against a fake
 * repository backed by the real SafeHavenFallback logic.
 */
class RouteViewModelTest {

    private class FakeRouteRepository(startGraph: RouteGraph) : RouteRepository {
        var currentGraph: RouteGraph = startGraph
        var findCount = 0

        override fun getGraph(): RouteGraph = currentGraph

        override fun saveGraph(graph: RouteGraph) {
            currentGraph = graph
        }

        override fun findRoute(startNodeId: String, destinationNodeId: String): com.example.georescux.domain.routing.EvacuationRoute? {
            findCount++
            return SafeHavenFallback.findRoute(currentGraph, startNodeId, destinationNodeId)
        }

        override fun setRoadBlocked(fromNodeId: String, toNodeId: String, blocked: Boolean) {
            currentGraph = currentGraph.copy(
                edges = currentGraph.edges.map {
                    if (it.connects(fromNodeId, toNodeId)) it.copy(blocked = blocked) else it
                }
            )
        }

        override fun setHazard(hazard: com.example.georescux.domain.routing.RoadHazard) {
            currentGraph = currentGraph.copy(hazards = currentGraph.hazards.filterNot { it.id == hazard.id } + hazard)
        }

        override fun removeHazard(hazardId: String) {
            currentGraph = currentGraph.copy(hazards = currentGraph.hazards.filterNot { it.id == hazardId })
        }
    }

    private fun graph(withDetachedNode: Boolean = false) = RouteGraph(
        nodes = buildList {
            add(RouteNode("a", 0.0, 0.0))
            add(RouteNode("b", 0.001, 0.0))
            add(RouteNode("c", 0.002, 0.0))
            if (withDetachedNode) add(RouteNode("z", 5.0, 5.0))
        },
        edges = listOf(
            RouteEdge("a", "b", 150.0),
            RouteEdge("b", "c", 150.0),
            RouteEdge("a", "c", 400.0),
        ),
    )

    @Test
    fun `route state is exposed after finding a route`() {
        val viewModel = RouteViewModel(FakeRouteRepository(graph()))

        viewModel.findRoute("a", "c")

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b", "c"), state.route?.nodeIds)
        assertEquals(300.0, state.route?.totalCostMeters)
        assertFalse(state.routeNotFound)
        assertEquals("a", state.startNodeId)
        assertEquals("c", state.destinationNodeId)
    }

    @Test
    fun `unreachable destination produces the not-found state`() {
        val viewModel = RouteViewModel(FakeRouteRepository(graph(withDetachedNode = true)))

        viewModel.findRoute("a", "z")

        assertNull(viewModel.uiState.value.route)
        assertTrue(viewModel.uiState.value.routeNotFound)
    }

    @Test
    fun `reroute recomputes with the current graph state`() {
        val repository = FakeRouteRepository(graph())
        val viewModel = RouteViewModel(repository)
        viewModel.findRoute("a", "c")

        // A road becomes blocked; rerouting must reflect the new state.
        repository.setRoadBlocked("a", "b", blocked = true)
        viewModel.reroute()

        assertEquals(listOf("a", "c"), viewModel.uiState.value.route?.nodeIds)
        assertEquals(400.0, viewModel.uiState.value.route?.totalCostMeters)
    }

    @Test
    fun `unknown destination does not crash the view model`() {
        val viewModel = RouteViewModel(FakeRouteRepository(graph()))

        viewModel.findRoute("a", "unknown")

        assertNull(viewModel.uiState.value.route)
        assertTrue(viewModel.uiState.value.routeNotFound)
    }

    @Test
    fun `blank input leaves the state unchanged`() {
        val viewModel = RouteViewModel(FakeRouteRepository(graph()))

        viewModel.findRoute("", "c")

        assertNull(viewModel.uiState.value.route)
        assertFalse(viewModel.uiState.value.routeNotFound)
    }

    // ---- Reroute is never a silent no-op (real-device reroute fix) ----

    private fun safeHavenGraph() = RouteGraph(
        nodes = listOf(
            RouteNode("a", 0.0, 0.0),
            RouteNode("b", 0.001, 0.0),
            RouteNode("c", 0.002, 0.0, isSafeHaven = true),
        ),
        edges = listOf(
            RouteEdge("a", "b", 150.0),
            RouteEdge("b", "c", 150.0),
        ),
    )

    @Test
    fun `reroute with both ids already populated recomputes the same route`() {
        val viewModel = RouteViewModel(FakeRouteRepository(graph()))
        viewModel.findRoute("a", "c")

        // Exactly what the Activity passes: both current spinner selections.
        viewModel.reroute(fallbackStartNodeId = "b", fallbackDestinationNodeId = "c")

        assertEquals(listOf("a", "b", "c"), viewModel.uiState.value.route?.nodeIds)
        assertEquals("a", viewModel.uiState.value.startNodeId) // GPS state start wins over fallback
        assertEquals("c", viewModel.uiState.value.destinationNodeId)
        assertFalse(viewModel.uiState.value.selectionRequired)
    }

    @Test
    fun `reroute with a missing destination falls back to the first safe haven`() {
        val viewModel = RouteViewModel(FakeRouteRepository(safeHavenGraph()))

        // GPS-style start selection only — no destination has been chosen yet.
        viewModel.setStartFromLocation(0.001, 0.0) // nearest node = b
        viewModel.reroute(fallbackStartNodeId = "a", fallbackDestinationNodeId = null)

        assertEquals("b", viewModel.uiState.value.startNodeId) // GPS start beats the fallback start
        assertEquals("c", viewModel.uiState.value.destinationNodeId) // first safe haven
        assertEquals(listOf("b", "c"), viewModel.uiState.value.route?.nodeIds)
        assertFalse(viewModel.uiState.value.selectionRequired)
    }

    @Test
    fun `reroute with a missing start resolves the screen-selected start`() {
        val viewModel = RouteViewModel(FakeRouteRepository(safeHavenGraph()))

        viewModel.reroute(fallbackStartNodeId = "a", fallbackDestinationNodeId = null)

        assertEquals("a", viewModel.uiState.value.startNodeId)
        assertEquals("c", viewModel.uiState.value.destinationNodeId) // first safe haven
        assertEquals(listOf("a", "b", "c"), viewModel.uiState.value.route?.nodeIds)
        assertFalse(viewModel.uiState.value.selectionRequired)
    }

    @Test
    fun `reroute without resolvable start or destination exposes selection-required feedback`() {
        val viewModel = RouteViewModel(FakeRouteRepository(graph())) // no safe havens

        viewModel.reroute(fallbackStartNodeId = null, fallbackDestinationNodeId = null)

        assertTrue(viewModel.uiState.value.selectionRequired)
        assertNull(viewModel.uiState.value.route)
        assertFalse(viewModel.uiState.value.routeNotFound)
    }

    @Test
    fun `current destination spinner selection overrides the stale state destination`() {
        val viewModel = RouteViewModel(FakeRouteRepository(safeHavenGraph()))
        viewModel.findRoute("a", "b") // state destination is now stale ("b")

        // The user changed the destination spinner to the safe haven and presses Reroute.
        viewModel.reroute(fallbackStartNodeId = "a", fallbackDestinationNodeId = "c")

        assertEquals("c", viewModel.uiState.value.destinationNodeId) // spinner wins over stale state
        assertEquals(listOf("a", "b", "c"), viewModel.uiState.value.route?.nodeIds)
    }

    @Test
    fun `find route after a GPS-selected start uses the GPS-selected node`() {
        val viewModel = RouteViewModel(FakeRouteRepository(graph()))

        viewModel.setStartFromLocation(0.001, 0.0) // nearest node = b
        viewModel.findRouteFromScreen(selectedStartNodeId = "a", destinationNodeId = "c") // stale spinner shows a

        assertEquals("b", viewModel.uiState.value.startNodeId) // GPS start wins pre-route
        assertEquals(listOf("b", "c"), viewModel.uiState.value.route?.nodeIds)
    }

    @Test
    fun `an explicit start selection after a route exists wins over the GPS start`() {
        val viewModel = RouteViewModel(FakeRouteRepository(graph()))
        viewModel.findRoute("a", "c")

        viewModel.setStartFromLocation(0.001, 0.0) // a later GPS fix selects b
        viewModel.findRouteFromScreen(selectedStartNodeId = "a", destinationNodeId = "c")

        assertEquals("a", viewModel.uiState.value.startNodeId) // manual selection wins
        assertEquals(listOf("a", "b", "c"), viewModel.uiState.value.route?.nodeIds)
    }
}

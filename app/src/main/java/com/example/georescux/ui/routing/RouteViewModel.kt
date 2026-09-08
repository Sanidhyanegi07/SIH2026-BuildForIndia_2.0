package com.example.georescux.ui.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.domain.repository.RouteRepository
import com.example.georescux.domain.routing.EvacuationRoute
import com.example.georescux.domain.routing.NearestNode
import com.example.georescux.domain.routing.RouteGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Safe Route screen currently shows.
 */
data class RouteUiState(
    val graph: RouteGraph? = null,
    val route: EvacuationRoute? = null,
    val routeNotFound: Boolean = false,
    val startNodeId: String? = null,
    val destinationNodeId: String? = null,
    /** Reroute could not resolve a start/destination — tell the user to select them. */
    val selectionRequired: Boolean = false,
)

/**
 * Offline evacuation routing: exposes the road graph for selection and the
 * computed route. All work goes through [RouteRepository] (local, hazard
 * and blocked-road aware); the ViewModel contains no routing logic.
 */
class RouteViewModel(private val routeRepository: RouteRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteUiState(graph = routeRepository.getGraph()))
    val uiState: StateFlow<RouteUiState> = _uiState.asStateFlow()

    fun findRoute(startNodeId: String, destinationNodeId: String) {
        if (startNodeId.isBlank() || destinationNodeId.isBlank()) return
        val route = routeRepository.findRoute(startNodeId, destinationNodeId)
        _uiState.value = RouteUiState(
            graph = _uiState.value.graph,
            route = route,
            routeNotFound = route == null,
            startNodeId = startNodeId,
            destinationNodeId = destinationNodeId,
        )
    }

    /**
     * Find Route as pressed on the screen: while no route has been
     * established yet, the GPS-selected start ([RouteUiState.startNodeId])
     * wins over the possibly-stale default spinner position; after a route
     * exists the screen's selection is authoritative, because the user may
     * have changed the start spinner manually.
     */
    fun findRouteFromScreen(selectedStartNodeId: String?, destinationNodeId: String?) {
        val state = _uiState.value
        val preferGpsStart = state.route == null && !state.routeNotFound
        val startNodeId = if (preferGpsStart) {
            state.startNodeId ?: selectedStartNodeId
        } else {
            selectedStartNodeId
        }
        if (startNodeId == null || destinationNodeId == null) return
        findRoute(startNodeId, destinationNodeId)
    }

    /**
     * Recomputes the route using the current graph/hazard state. Never a
     * silent no-op. Destination resolution priority: the screen's
     * CURRENTLY selected destination node ([fallbackDestinationNodeId] —
     * what the user sees, which may have changed since the last route),
     * then the last-used destination ([RouteUiState.destinationNodeId]),
     * then the deterministic spinner default (the first safe haven). Start
     * resolution priority: the GPS-selected
     * [RouteUiState.startNodeId] first, then the screen's selected start
     * node ([fallbackStartNodeId]). When even that cannot be resolved,
     * [RouteUiState.selectionRequired] tells the user to select a start
     * and destination.
     */
    fun reroute(fallbackStartNodeId: String? = null, fallbackDestinationNodeId: String? = null) {
        val current = _uiState.value
        val start = current.startNodeId
            ?: fallbackStartNodeId?.takeIf { it.isNotBlank() }
        val destination = fallbackDestinationNodeId?.takeIf { it.isNotBlank() }
            ?: current.destinationNodeId
            ?: firstSafeHavenId(current.graph)
        if (start == null || destination == null) {
            _uiState.value = current.copy(selectionRequired = true)
            return
        }
        findRoute(start, destination)
    }

    /** Selects the graph node nearest to the current location as the start. */
    fun setStartFromLocation(latitude: Double, longitude: Double) {
        val graph = _uiState.value.graph ?: return
        val nearest = NearestNode.find(graph, latitude, longitude) ?: return
        _uiState.value = _uiState.value.copy(startNodeId = nearest.id)
    }

    /** The deterministic destination default used by the destination spinner. */
    private fun firstSafeHavenId(graph: RouteGraph?): String? =
        graph?.nodes?.firstOrNull { it.isSafeHaven }?.id

    class Factory(private val routeRepository: RouteRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RouteViewModel(routeRepository) as T
        }
    }
}

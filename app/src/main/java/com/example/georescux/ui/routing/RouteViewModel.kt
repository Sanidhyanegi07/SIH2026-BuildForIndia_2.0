package com.example.georescux.ui.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.georescux.domain.repository.RouteRepository
import com.example.georescux.domain.routing.EvacuationRoute
import com.example.georescux.domain.routing.NearestNode
import com.example.georescux.domain.routing.RouteGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** Stage 7B-4: the regional graph (megabyte-scale) is being loaded off the main thread. */
    val graphLoading: Boolean = true,
)

/**
 * Offline evacuation routing: exposes the road graph for selection and the
 * computed route. All work goes through [RouteRepository] (local, hazard
 * and blocked-road aware); the ViewModel contains no routing logic.
 *
 * Stage 7B-4: the graph load and every route calculation run on
 * [Dispatchers.Default] — a real regional graph is megabyte-scale and must
 * never touch the main thread. A location fix that arrives before the
 * graph is ready is remembered and applied once loading completes.
 */
class RouteViewModel(
    private val routeRepository: RouteRepository,
    /** Injected for tests; production runs every heavy step off the main thread. */
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    // Same explicit-scope pattern as RouteActivity's uiScope (the project
    // does not depend on lifecycle-viewmodel-ktx for viewModelScope).
    private val vmScope = CoroutineScope(SupervisorJob() + loadDispatcher)

    private val _uiState = MutableStateFlow(RouteUiState())
    val uiState: StateFlow<RouteUiState> = _uiState.asStateFlow()

    private var pendingStartLocation: Pair<Double, Double>? = null

    init {
        vmScope.launch {
            val graph = routeRepository.getGraph()
            _uiState.update { state ->
                state.copy(graph = graph, graphLoading = false)
            }
            pendingStartLocation?.let { (latitude, longitude) ->
                pendingStartLocation = null
                applyStartFromLocation(latitude, longitude)
            }
        }
    }

    override fun onCleared() {
        vmScope.cancel()
        super.onCleared()
    }

    fun findRoute(startNodeId: String, destinationNodeId: String) {
        if (startNodeId.isBlank() || destinationNodeId.isBlank()) return
        vmScope.launch {
            val route = routeRepository.findRoute(startNodeId, destinationNodeId)
            _uiState.update { state ->
                state.copy(
                    graph = state.graph,
                    route = route,
                    routeNotFound = route == null,
                    startNodeId = startNodeId,
                    destinationNodeId = destinationNodeId,
                )
            }
        }
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
            _uiState.update { it.copy(selectionRequired = true) }
            return
        }
        findRoute(start, destination)
    }

    /** Selects the graph node nearest to the current location as the start. */
    fun setStartFromLocation(latitude: Double, longitude: Double) {
        val graph = _uiState.value.graph
        if (graph == null) {
            pendingStartLocation = latitude to longitude
            return
        }
        applyStartFromLocation(latitude, longitude)
    }

    private fun applyStartFromLocation(latitude: Double, longitude: Double) {
        val graph = _uiState.value.graph ?: return
        val nearest = NearestNode.find(graph, latitude, longitude) ?: return
        _uiState.update { it.copy(startNodeId = nearest.id) }
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

package com.example.georescux.data.routing

import com.example.georescux.domain.repository.RouteRepository
import com.example.georescux.domain.routing.EvacuationRoute
import com.example.georescux.domain.routing.RouteGraph
import com.example.georescux.domain.routing.RoadHazard
import com.example.georescux.domain.routing.SafeHavenFallback

/**
 * Local-first routing repository. The graph is stored on-device and all
 * routing runs on-device via SafeHavenFallback. No network, no Firebase.
 *
 * Stage 7B-1: the graph is seeded from the bundled real OSM-derived sample
 * region when its version is newer than the stored graph; the synthetic
 * default graph remains the fallback.
 */
class RouteRepositoryImpl(
    private val store: RouteGraphLocalStore,
    private val activeRegionId: String,
    private val seedProvider: (() -> RouteGraph?)? = null,
) : RouteRepository {

    private val cachedSeed: RouteGraph? by lazy { seedProvider?.invoke() }

    override fun getGraph(): RouteGraph {
        store.migrateLegacyGraph(activeRegionId)
        val stored = store.loadGraph(activeRegionId)
        val seed = cachedSeed
        // A newer bundled region upgrades the stored graph (e.g. replaces the
        // synthetic v0 sample with the real OSM-derived region).
        if (seed != null && stored != null && stored.version >= seed.version) return stored
        return (seed ?: stored ?: DefaultEvacuationGraph.create()).also { store.saveGraph(activeRegionId, it) }
    }

    override fun saveGraph(graph: RouteGraph) = store.saveGraph(activeRegionId, graph)

    override fun findRoute(startNodeId: String, destinationNodeId: String): EvacuationRoute? =
        SafeHavenFallback.findRoute(getGraph(), startNodeId, destinationNodeId)

    override fun setRoadBlocked(fromNodeId: String, toNodeId: String, blocked: Boolean) {
        val graph = getGraph()
        saveGraph(
            graph.copy(
                edges = graph.edges.map { edge ->
                    if (edge.connects(fromNodeId, toNodeId)) edge.copy(blocked = blocked) else edge
                }
            )
        )
    }

    override fun setHazard(hazard: RoadHazard) {
        val graph = getGraph()
        saveGraph(graph.copy(hazards = graph.hazards.filterNot { it.id == hazard.id } + hazard))
    }

    override fun removeHazard(hazardId: String) {
        val graph = getGraph()
        saveGraph(graph.copy(hazards = graph.hazards.filterNot { it.id == hazardId }))
    }
}

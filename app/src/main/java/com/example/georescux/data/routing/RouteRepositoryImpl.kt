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
 *
 * Stage 7B-4: the resolved graph is memoized. Regional graphs are
 * megabyte-scale JSON; every findRoute/setHazard call goes through
 * [getGraph], which must not re-decode the stored graph each time. The
 * cache is synchronized because graph loads run on a worker dispatcher
 * while hazard updates can arrive from the mesh relay on other threads.
 */
class RouteRepositoryImpl(
    private val store: RouteGraphLocalStore,
    private val activeRegionId: String,
    private val seedProvider: (() -> RouteGraph?)? = null,
    /** Catalog version of the bundled seed; lets getGraph skip parsing the (megabyte-scale) seed asset when the stored graph is already current. 0 = unknown (always consult the seed). */
    private val bundledSeedVersion: Int = 0,
) : RouteRepository {

    private val cachedSeed: RouteGraph? by lazy { seedProvider?.invoke() }

    private var cachedGraph: RouteGraph? = null

    @Synchronized
    override fun getGraph(): RouteGraph {
        cachedGraph?.let { return it }
        store.migrateLegacyGraph(activeRegionId)
        val stored = store.loadGraph(activeRegionId)
        // The stored graph is current when it matches the bundled catalog
        // version — the seed asset does not need to be parsed at all.
        if (stored != null && bundledSeedVersion > 0 && stored.version >= bundledSeedVersion) {
            cachedGraph = stored
            return stored
        }
        val seed = cachedSeed
        // A newer bundled region upgrades the stored graph (e.g. replaces the
        // synthetic v0 sample with the real OSM-derived region).
        if (seed != null && stored != null && stored.version >= seed.version) {
            cachedGraph = stored
            return stored
        }
        val resolved = (seed ?: stored ?: DefaultEvacuationGraph.create())
            .also { store.saveGraph(activeRegionId, it) }
        cachedGraph = resolved
        return resolved
    }

    @Synchronized
    override fun saveGraph(graph: RouteGraph) {
        cachedGraph = graph
        store.saveGraph(activeRegionId, graph)
    }

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

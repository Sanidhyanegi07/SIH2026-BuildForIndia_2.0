package com.example.georescux.data.routing

import com.example.georescux.domain.routing.RouteGraph

/**
 * Local on-device storage for road graphs, scoped per map region.
 *
 * Each region stores its graph under its own key, so installing or
 * upgrading one region never touches another region's graph.
 * [migrateLegacyGraph] retires the pre-7B-2 single-graph storage
 * ("route_graph") by moving it into the region namespace exactly once.
 */
interface RouteGraphLocalStore {
    /**
     * One-time legacy migration hook. Implementations without pre-7B-2
     * local data keep the default no-op.
     */
    fun migrateLegacyGraph(regionId: String) {}

    fun loadGraph(regionId: String): RouteGraph?

    fun saveGraph(regionId: String, graph: RouteGraph)
}

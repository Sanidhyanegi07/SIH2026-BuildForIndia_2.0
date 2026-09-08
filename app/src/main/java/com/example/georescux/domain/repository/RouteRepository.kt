package com.example.georescux.domain.repository

import com.example.georescux.domain.routing.EvacuationRoute
import com.example.georescux.domain.routing.RouteGraph
import com.example.georescux.domain.routing.RoadHazard

/**
 * Offline evacuation-routing operations. The graph is stored locally and
 * all routing runs on-device; no implementation details are exposed.
 */
interface RouteRepository {
    /** The current road graph (seeded on first use if none was saved yet). */
    fun getGraph(): RouteGraph

    /** Replaces the locally stored road graph. */
    fun saveGraph(graph: RouteGraph)

    /**
     * Finds the best evacuation route: the requested destination first, or
     * the nearest reachable safe haven when it is unreachable. Null when
     * no route exists at all.
     */
    fun findRoute(startNodeId: String, destinationNodeId: String): EvacuationRoute?

    /** Blocks or unblocks the undirected road between two nodes. */
    fun setRoadBlocked(fromNodeId: String, toNodeId: String, blocked: Boolean)

    /** Adds or updates a road hazard (id-keyed). */
    fun setHazard(hazard: RoadHazard)

    /** Removes a road hazard by id. */
    fun removeHazard(hazardId: String)
}

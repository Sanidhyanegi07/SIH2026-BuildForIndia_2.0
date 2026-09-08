package com.example.georescux.domain.routing

/**
 * The offline road graph: nodes, undirected edges and road hazards.
 * [version] identifies the graph generation (0 = synthetic sample, 1 =
 * bundled real OSM-derived region, …) so a stored graph can be upgraded
 * when a newer map region ships. Adjacency is prebuilt once (in
 * deterministic edge order) for A*. Pure Kotlin — no Android, Firebase, or
 * UI code.
 */
data class RouteGraph(
    val nodes: List<RouteNode>,
    val edges: List<RouteEdge>,
    val hazards: List<RoadHazard> = emptyList(),
    val version: Int = 0,
) {
    private val nodesById: Map<String, RouteNode> = nodes.associateBy { it.id }

    /** Undirected adjacency: nodeId -> (edge, neighborNode), in edge order. */
    private val adjacency: Map<String, List<Pair<RouteEdge, RouteNode>>> = buildAdjacency()

    fun node(nodeId: String): RouteNode? = nodesById[nodeId]

    fun neighbors(nodeId: String): List<Pair<RouteEdge, RouteNode>> = adjacency[nodeId] ?: emptyList()

    /** The undirected edge between two nodes, or null when not directly connected. */
    fun edgeBetween(fromNodeId: String, toNodeId: String): RouteEdge? =
        edges.firstOrNull { it.connects(fromNodeId, toNodeId) }

    private fun buildAdjacency(): Map<String, List<Pair<RouteEdge, RouteNode>>> {
        val adjacency = mutableMapOf<String, MutableList<Pair<RouteEdge, RouteNode>>>()
        edges.forEach { edge ->
            val from = nodesById[edge.fromNodeId]
            val to = nodesById[edge.toNodeId]
            if (from != null && to != null) {
                adjacency.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge to to)
                adjacency.getOrPut(edge.toNodeId) { mutableListOf() }.add(edge to from)
            }
        }
        return adjacency
    }
}

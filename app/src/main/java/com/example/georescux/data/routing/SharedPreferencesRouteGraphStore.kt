package com.example.georescux.data.routing

import android.content.Context
import com.example.georescux.domain.routing.RouteEdge
import com.example.georescux.domain.routing.RouteGraph
import com.example.georescux.domain.routing.RouteNode
import com.example.georescux.domain.routing.RoadHazard
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed road-graph store, scoped per map region:
 * each region's graph lives under its own key ("route_graph_{regionId}"),
 * so regions never overwrite each other.
 *
 * Stage 7B-2 also performs the one-time legacy migration: the pre-7B-2
 * unscoped "route_graph" key is moved into the region namespace exactly
 * once; a corrupt legacy graph is retired and normal region seeding
 * proceeds instead.
 */
class SharedPreferencesRouteGraphStore(context: Context) : RouteGraphLocalStore {

    private val prefs = context.getSharedPreferences("routing_store", Context.MODE_PRIVATE)

    override fun migrateLegacyGraph(regionId: String) {
        val legacyJson = prefs.getString(LEGACY_KEY, null) ?: return
        val outcome = RouteGraphLegacyMigrator.resolve(legacyJson, prefs.contains(key(regionId)))

        val editor = prefs.edit()
        outcome.writeToRegion?.let { editor.putString(key(regionId), encode(it).toString()) }
        if (outcome.retireLegacyKey) editor.remove(LEGACY_KEY)
        editor.apply()
    }

    override fun loadGraph(regionId: String): RouteGraph? {
        migrateLegacyGraph(regionId)
        val json = prefs.getString(key(regionId), null) ?: return null
        // A corrupt stored graph must not crash the app or brick routing:
        // fail safely (null) so the repository falls back to the seed.
        return runCatching { decode(json) }.getOrNull()
    }

    override fun saveGraph(regionId: String, graph: RouteGraph) {
        prefs.edit().putString(key(regionId), encode(graph).toString()).apply()
    }

    private fun key(regionId: String) = "route_graph_$regionId"

    private fun encode(graph: RouteGraph): JSONObject = JSONObject().apply {
        put("version", graph.version)
        put("nodes", JSONArray().apply {
            graph.nodes.forEach { node ->
                put(JSONObject().apply {
                    put("id", node.id)
                    put("latitude", node.latitude)
                    put("longitude", node.longitude)
                    put("isSafeHaven", node.isSafeHaven)
                })
            }
        })
        put("edges", JSONArray().apply {
            graph.edges.forEach { edge ->
                put(JSONObject().apply {
                    put("fromNodeId", edge.fromNodeId)
                    put("toNodeId", edge.toNodeId)
                    put("distanceMeters", edge.distanceMeters)
                    put("blocked", edge.blocked)
                })
            }
        })
        put("hazards", JSONArray().apply {
            graph.hazards.forEach { hazard ->
                put(JSONObject().apply {
                    put("id", hazard.id)
                    put("fromNodeId", hazard.fromNodeId)
                    put("toNodeId", hazard.toNodeId)
                    put("penaltyMeters", hazard.penaltyMeters)
                })
            }
        })
    }

    private fun decode(json: String): RouteGraph {
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        val nodes = JSONArray(root.getString("nodes")).let { array ->
            (0 until array.length()).map { index ->
                val node = array.getJSONObject(index)
                RouteNode(
                    id = node.getString("id"),
                    latitude = node.getDouble("latitude"),
                    longitude = node.getDouble("longitude"),
                    isSafeHaven = node.optBoolean("isSafeHaven", false),
                )
            }
        }
        val edges = JSONArray(root.getString("edges")).let { array ->
            (0 until array.length()).map { index ->
                val edge = array.getJSONObject(index)
                RouteEdge(
                    fromNodeId = edge.getString("fromNodeId"),
                    toNodeId = edge.getString("toNodeId"),
                    distanceMeters = edge.getDouble("distanceMeters"),
                    blocked = edge.optBoolean("blocked", false),
                )
            }
        }
        val hazards = root.optJSONArray("hazards")?.let { array ->
            (0 until array.length()).map { index ->
                val hazard = array.getJSONObject(index)
                RoadHazard(
                    id = hazard.getString("id"),
                    fromNodeId = hazard.getString("fromNodeId"),
                    toNodeId = hazard.getString("toNodeId"),
                    penaltyMeters = hazard.getDouble("penaltyMeters"),
                )
            }
        } ?: emptyList()
        return RouteGraph(nodes = nodes, edges = edges, hazards = hazards, version = version)
    }

    private companion object {
        const val LEGACY_KEY = "route_graph"
    }
}

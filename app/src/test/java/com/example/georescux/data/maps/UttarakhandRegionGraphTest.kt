package com.example.georescux.data.maps

import com.example.georescux.domain.routing.AStarPathfinder
import com.example.georescux.domain.routing.NearestNode
import com.example.georescux.domain.routing.RouteEdge
import com.example.georescux.domain.routing.RouteGraph
import com.example.georescux.domain.routing.SafeHavenFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.random.Random

/**
 * Stage 7B-4 acceptance tests for the bundled real Uttarakhand regional
 * graph (produced by tools/uttarakhand/BuildRegionGraph.java from Geofabrik
 * OSM extracts):
 *
 *  1. structural validation of the bundled asset,
 *  2. a connectivity audit (no silently unreachable bulk of the region),
 *  3. an A* vs Dijkstra oracle cross-check on real routes,
 *  4. a real before/after hazard-rerouting scenario (Rishikesh → Joshimath,
 *     the landslide-prone NH-7 corridor) and a severed-destination safe
 *     haven fallback,
 *  5. JVM load/route performance numbers (printed; the emulator numbers in
 *     docs/UTTARAKHAND_REGION.md were measured separately on-device).
 */
class UttarakhandRegionGraphTest {

    companion object {
        /** Parsed once for the whole class — the asset is megabyte-scale. */
        private val loadedGraph: RouteGraph by lazy {
            val assetFile = java.io.File("src/main/assets/route_graph_uttarakhand")
            if (!assetFile.exists()) {
                throw AssertionError(
                    "bundled uttarakhand asset missing — run tools/uttarakhand/BuildRegionGraph.java"
                )
            }
            val t0 = System.currentTimeMillis()
            val json = assetFile.readText()
            val afterRead = System.currentTimeMillis()
            val parsed = RegionGraphLoader.parse(json)!!
            val afterParse = System.currentTimeMillis()
            // Rebuild the engine-facing structure so adjacency construction
            // is measured separately from JSON decoding.
            val rebuilt = RouteGraph(parsed.nodes, parsed.edges, parsed.hazards, parsed.version)
            val afterBuild = System.currentTimeMillis()
            println(
                "[perf] uttarakhand asset: read=${afterRead - t0} ms, " +
                    "miniJsonParse=${afterParse - afterRead} ms, " +
                    "graphBuild=${afterBuild - afterParse} ms, " +
                    "nodes=${rebuilt.nodes.size}, edges=${rebuilt.edges.size}"
            )
            rebuilt
        }

        private fun graph(): RouteGraph = loadedGraph
    }

    // ---- 1. Structure ----

    @Test
    fun `uttarakhand asset parses at real regional scale`() {
        val g = graph()

        assertTrue(
            "expected a state-scale graph, got ${g.nodes.size} nodes",
            g.nodes.size >= 50_000,
        )
        assertTrue(
            "expected a state-scale graph, got ${g.edges.size} edges",
            g.edges.size >= 50_000,
        )
        assertEquals(1, g.version)

        // Every node must sit near the region bbox (complete-way inclusion
        // may push border-road nodes slightly past the bbox edge — allow a
        // half-degree margin).
        val margin = 0.5
        g.nodes.forEach { node ->
            assertTrue(
                "node ${node.id} outside region bounds: ${node.latitude}, ${node.longitude}",
                node.latitude in (MapRegionCatalog.uttarakhand.minLatitude - margin)..(MapRegionCatalog.uttarakhand.maxLatitude + margin) &&
                    node.longitude in (MapRegionCatalog.uttarakhand.minLongitude - margin)..(MapRegionCatalog.uttarakhand.maxLongitude + margin),
            )
        }

        // Every edge must reference real nodes and carry a positive distance.
        g.edges.forEach { edge ->
            assertNotNull("edge ${edge.fromNodeId}->${edge.toNodeId} has unknown from-node", g.node(edge.fromNodeId))
            assertNotNull("edge ${edge.fromNodeId}->${edge.toNodeId} has unknown to-node", g.node(edge.toNodeId))
            assertTrue("edge with non-positive distance", edge.distanceMeters > 0.0)
        }
    }

    @Test
    fun `safe havens are real and plentiful`() {
        val g = graph()
        val havens = g.nodes.filter { it.isSafeHaven }
        assertTrue(
            "expected dozens of real safe havens, got ${havens.size}",
            havens.size in 20..64, // MAX_SAFE_HAVENS = 64 (fallback runs one A* per haven)
        )
    }

    // ---- 2. Connectivity ----

    @Test
    fun `connectivity audit - the overwhelming majority of the region is routable`() {
        val g = graph()
        val parent = HashMap<String, String>()
        g.nodes.forEach { parent[it.id] = it.id }

        fun find(i: String): String {
            var root = i
            while (parent[root] != root) root = parent.getValue(root)
            var cur = i
            while (parent[cur] != cur) {
                val next = parent.getValue(cur)
                parent[cur] = root
                cur = next
            }
            return root
        }

        g.edges.forEach { edge -> parent[find(edge.fromNodeId)] = find(edge.toNodeId) }

        val componentSizes = HashMap<String, Int>()
        g.nodes.forEach { componentSizes.merge(find(it.id), 1, Int::plus) }
        val largest = componentSizes.values.max()
        val fraction = largest.toDouble() / g.nodes.size

        println(
            "[connectivity] components=${componentSizes.size}, largest=$largest " +
                "(${String.format("%.2f", fraction * 100)}% of ${g.nodes.size} nodes), " +
                "islands=${componentSizes.values.sortedDescending().take(10)}"
        )
        // Islands are expected (ferry-less tracks, clipped borders, tagging
        // artifacts) — measured reality for this region is ~92% in the main
        // component. They must stay a small minority, and the engine
        // honestly reports "no route" for them instead of pretending.
        assertTrue(
            "main component covers only ${String.format("%.2f", fraction * 100)}% of nodes",
            fraction >= 0.90,
        )
    }

    // ---- 3. A* vs Dijkstra oracle ----

    /** Independent shortest-path implementation used as the correctness oracle. */
    private fun dijkstraCost(g: RouteGraph, startId: String, destinationId: String): Double? {
        val dist = HashMap<String, Double>()
        dist[startId] = 0.0
        val open = PriorityQueue<Pair<Double, String>>(compareBy { it.first })
        open.add(0.0 to startId)
        while (open.isNotEmpty()) {
            val (cost, nodeId) = open.poll()
            if (cost > (dist[nodeId] ?: Double.MAX_VALUE)) continue
            if (nodeId == destinationId) return cost
            for ((edge, neighbor) in g.neighbors(nodeId)) {
                if (edge.blocked) continue
                val next = cost + edge.distanceMeters
                if (next < (dist[neighbor.id] ?: Double.MAX_VALUE)) {
                    dist[neighbor.id] = next
                    open.add(next to neighbor.id)
                }
            }
        }
        return null
    }

    @Test
    fun `a star matches the dijkstra oracle on sampled real routes`() {
        val g = graph()
        val mainComponent = mainComponentIds(g)
        val routableNodes = g.nodes.filter { it.id in mainComponent }

        val pairs = mutableListOf<Pair<String, String>>()
        // Deterministic random pairs across the whole region…
        val rng = Random(42)
        while (pairs.size < 24) {
            val a = routableNodes[rng.nextInt(routableNodes.size)].id
            val b = routableNodes[rng.nextInt(routableNodes.size)].id
            if (a != b) pairs.add(a to b)
        }
        // …plus real geographic corridors snapped from real towns:
        // Dehradun→Haldwani and Gangotri→Pithoragarh.
        val dehradun = NearestNode.find(g, 30.3165, 78.0322)!!
        val haldwani = NearestNode.find(g, 29.2183, 79.5130)!!
        val gangotri = NearestNode.find(g, 30.9926, 78.9336)!!
        val pithoragarh = NearestNode.find(g, 29.5833, 80.2167)!!
        pairs.add(dehradun.id to haldwani.id)
        pairs.add(gangotri.id to pithoragarh.id)

        var totalMs = 0L
        var maxMs = 0L
        for ((startId, destId) in pairs) {
            val t0 = System.nanoTime()
            val route = AStarPathfinder.findRoute(g, startId, destId)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            totalMs += elapsedMs
            maxMs = maxOf(maxMs, elapsedMs)

            assertNotNull("no route found: $startId -> $destId", route)
            assertEquals(startId, route!!.nodeIds.first())
            assertEquals(destId, route.nodeIds.last())

            // Path integrity: every leg is a real, unblocked edge.
            route.nodeIds.zipWithNext { from, to ->
                val edge = g.edgeBetween(from, to)
                assertNotNull("route leg without edge: $from -> $to", edge)
                assertTrue("route leg uses blocked edge", !edge!!.blocked)
            }

            val expected = dijkstraCost(g, startId, destId)
            assertNotNull("oracle found no route where A* did", expected)
            val tolerance = 1e-6 * maxOf(1.0, expected!!)
            assertTrue(
                "A* cost ${route.totalCostMeters} != oracle cost $expected for $startId -> $destId",
                abs(route.totalCostMeters - expected) <= tolerance,
            )
        }
        println("[perf] a-star x${pairs.size}: avg=${totalMs / pairs.size} ms, max=$maxMs ms")
    }

    // ---- 4. Real hazard scenarios ----

    private fun snap(latitude: Double, longitude: Double) = NearestNode.find(graph(), latitude, longitude)!!.id

    @Test
    fun `blocking a landslide segment on the rishikesh-joshimath corridor forces a genuine detour`() {
        val g = graph()
        // NH-7 corridor: Rishikesh (30.0869, 78.2676) → Joshimath (30.4424, 79.5572).
        val start = snap(30.0869, 78.2676)
        val destination = snap(30.4424, 79.5572)

        val original = SafeHavenFallback.findRoute(g, start, destination)
        assertNotNull("NH-7 corridor must be routable in the real graph", original)
        assertTrue(original!!.totalDistanceMeters > 50_000.0) // ~250 km corridor

        // A landslide takes out the single longest leg of the original route.
        val blockedLeg = original.nodeIds.zipWithNext()
            .map { (from, to) -> Triple(from, to, g.edgeBetween(from, to)!!) }
            .maxByOrNull { it.third.distanceMeters }!!
        val blockFrom = blockedLeg.first
        val blockTo = blockedLeg.second
        val hazardGraph = g.copy(
            edges = g.edges.map { edge ->
                if (edge.connects(blockFrom, blockTo)) edge.copy(blocked = true) else edge
            }
        )

        val rerouted = SafeHavenFallback.findRoute(hazardGraph, start, destination)
        assertNotNull("reroute after blocking $blockFrom->$blockTo failed entirely", rerouted)
        assertEquals(start, rerouted!!.nodeIds.first())
        assertEquals(destination, rerouted.nodeIds.last())
        assertTrue(
            "rerouted path still uses the blocked road segment",
            rerouted.nodeIds.zipWithNext().none { (from, to) ->
                (from == blockFrom && to == blockTo) || (from == blockTo && to == blockFrom)
            },
        )
        assertTrue(
            "engine did not actually detour (identical path)",
            rerouted.nodeIds != original.nodeIds,
        )
        println(
            "[scenario] Rishikesh→Joshimath: original=${original.totalDistanceMeters / 1000} km, " +
                "blocked leg=${blockedLeg.third.distanceMeters / 1000} km " +
                "($blockFrom→$blockTo), reroute=${rerouted.totalDistanceMeters / 1000} km"
        )
    }

    @Test
    fun `severing the destination falls back to the nearest reachable safe haven`() {
        val g = graph()
        // Joshimath cut off entirely (e.g. valley blocked by a flash flood):
        // every road into the town is blocked.
        val destination = snap(30.4424, 79.5572)
        val severedGraph = g.copy(
            edges = g.edges.map { edge ->
                if (edge.isIncidentTo(destination)) edge.copy(blocked = true) else edge
            }
        )
        assertTrue(
            "severed town still has open roads",
            severedGraph.edges.none { !it.blocked && (it.fromNodeId == destination || it.toNodeId == destination) },
        )

        val start = snap(30.0869, 78.2676) // Rishikesh
        val t0 = System.currentTimeMillis()
        val route = SafeHavenFallback.findRoute(severedGraph, start, destination)
        val elapsed = System.currentTimeMillis() - t0

        assertNotNull("fallback must reach SOME safe haven, not fail silently", route)
        val lastNode = severedGraph.node(route!!.nodeIds.last())!!
        assertTrue(
            "fallback route must end at a safe haven, ended at ${lastNode.id}",
            lastNode.isSafeHaven,
        )
        assertTrue("fallback must not end at the severed town", lastNode.id != destination)
        assertTrue(
            "fallback route uses a blocked edge",
            route.nodeIds.zipWithNext().none { (from, to) -> severedGraph.edgeBetween(from, to)!!.blocked },
        )
        println("[perf] worst-case safe-haven fallback (all havens tried): $elapsed ms")
    }

    private fun RouteEdge.isIncidentTo(nodeId: String): Boolean =
        fromNodeId == nodeId || toNodeId == nodeId

    // ---- helpers ----

    private fun mainComponentIds(g: RouteGraph): Set<String> {
        val parent = HashMap<String, String>()
        g.nodes.forEach { parent[it.id] = it.id }
        fun find(i: String): String {
            var root = i
            while (parent[root] != root) root = parent.getValue(root)
            return root
        }
        g.edges.forEach { edge -> parent[find(edge.fromNodeId)] = find(edge.toNodeId) }
        val sizes = HashMap<String, Int>()
        g.nodes.forEach { sizes.merge(find(it.id), 1, Int::plus) }
        val mainRoot = sizes.maxByOrNull { it.value }!!.key
        return g.nodes.filter { find(it.id) == mainRoot }.map { it.id }.toSet()
    }
}

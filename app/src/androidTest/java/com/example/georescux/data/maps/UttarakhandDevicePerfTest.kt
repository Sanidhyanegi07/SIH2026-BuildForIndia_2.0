package com.example.georescux.data.maps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.georescux.domain.routing.AStarPathfinder
import com.example.georescux.domain.routing.NearestNode
import com.example.georescux.domain.routing.RouteGraph
import com.example.georescux.domain.routing.SafeHavenFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 7B-4 on-device performance measurement for the bundled Uttarakhand
 * regional graph: asset → RouteGraph load time, A* route-calculation time
 * (sampled real pairs + the Rishikesh→Joshimath NH-7 corridor) and the
 * worst-case safe-haven fallback. Run on an emulator/device via
 * `connectedDebugAndroidTest`; results are printed to logcat
 * (tag "GeoRoutePerf").
 */
@RunWith(AndroidJUnit4::class)
class UttarakhandDevicePerfTest {

    private fun loadGraph(): RouteGraph {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = context.assets.open("route_graph_uttarakhand").use { stream ->
            stream.bufferedReader().readText()
        }
        val t0 = System.currentTimeMillis()
        val parsed = RegionGraphLoader.parse(json)!!
        val afterParse = System.currentTimeMillis()
        val rebuilt = RouteGraph(parsed.nodes, parsed.edges, parsed.hazards, parsed.version)
        val afterBuild = System.currentTimeMillis()
        println(
            "GeoRoutePerf: load asset->graph: miniJsonParse=${afterParse - t0} ms, " +
                "graphBuild=${afterBuild - afterParse} ms, total=${afterBuild - t0} ms, " +
                "nodes=${rebuilt.nodes.size}, edges=${rebuilt.edges.size}"
        )
        return rebuilt
    }

    @Test
    fun measureLoadAndRoutePerformance() {
        val graph = loadGraph()

        // Real corridors snapped from real towns.
        val corridors = listOf(
            "Dehradun→Haldwani" to (30.3165 to 78.0322),
            "Rishikesh→Joshimath" to (30.0869 to 78.2676),
        )
        val destinations = listOf(29.2183 to 79.5130, 30.4424 to 79.5572)

        var totalMs = 0L
        var maxMs = 0L
        var runs = 0
        // Warm-up run (class loading, JIT) is measured separately and excluded.
        for ((name, start) in corridors) {
            for (dest in destinations) {
                val startNode = NearestNode.find(graph, start.first, start.second)!!
                val destNode = NearestNode.find(graph, dest.first, dest.second)!!
                val t0 = System.nanoTime()
                val route = SafeHavenFallback.findRoute(graph, startNode.id, destNode.id)
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertNotNull("no route for $name", route)
                println("GeoRoutePerf: route $name: ${ms} ms, ${route!!.nodeIds.size} legs, " +
                        "${route.totalDistanceMeters / 1000} km")
                if (runs >= 2) { // first two runs = warm-up
                    totalMs += ms
                    maxMs = maxOf(maxMs, ms)
                }
                runs++
            }
        }
        println("GeoRoutePerf: route avg (steady-state) = ${totalMs / (runs - 2)} ms, max = $maxMs ms")

        // Worst case: destination unreachable -> one A* per safe haven.
        val destination = NearestNode.find(graph, 30.4424, 79.5572)!!.id
        val severed = graph.copy(
            edges = graph.edges.map { edge ->
                if (edge.fromNodeId == destination || edge.toNodeId == destination) {
                    edge.copy(blocked = true)
                } else edge
            }
        )
        val start = NearestNode.find(graph, 30.0869, 78.2676)!!.id
        val t0 = System.currentTimeMillis()
        val fallback = SafeHavenFallback.findRoute(severed, start, destination)
        val fallbackMs = System.currentTimeMillis() - t0
        assertNotNull(fallback)
        println("GeoRoutePerf: worst-case safe-haven fallback: $fallbackMs ms -> " +
                "haven ${fallback!!.nodeIds.last()}")
        assertEquals(true, severed.node(fallback.nodeIds.last())!!.isSafeHaven)
    }
}

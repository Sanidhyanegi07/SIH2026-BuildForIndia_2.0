package com.example.georescux.domain.routing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Off-route detection (spec §15/§16).
 *
 * Computes how far a live GPS fix sits from the computed route and decides
 * when the user has drifted far enough that a recalculation is warranted —
 * "Reroute when the user deviates from route", triggered automatically
 * rather than only by the manual button.
 *
 * Pure and framework-free: distance-to-polyline over the route's node
 * coordinates using a local equirectangular projection. Road edges are
 * short, so planar error is negligible at this scale.
 */
object RouteDeviationDetector {

    /**
     * How far the user may be from the route before it counts as off-route.
     * Generous on purpose: GPS accuracy in the hills is tens of metres, and
     * a too-tight threshold would reroute on every noisy fix.
     */
    const val OFF_ROUTE_THRESHOLD_METERS = 250.0

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Distance from a fix to the nearest point of the route, in metres. */
    fun distanceToRouteMeters(
        latitude: Double,
        longitude: Double,
        route: EvacuationRoute,
        graph: RouteGraph,
    ): Double {
        if (route.nodeIds.size < 2) return Double.MAX_VALUE
        val referenceLatitude = Math.toRadians(latitude)
        val cosRef = cos(referenceLatitude)
        val metresPerDegreeLat = EARTH_RADIUS_METERS * PI / 180.0
        val metresPerDegreeLng = EARTH_RADIUS_METERS * PI / 180.0 * cosRef

        val points = route.nodeIds.mapNotNull { graph.node(it) }
        if (points.size < 2) return Double.MAX_VALUE

        // Fix position in metres relative to its own parallel.
        val px = longitude * metresPerDegreeLng
        val py = latitude * metresPerDegreeLat

        var minimum = Double.MAX_VALUE
        points.zipWithNext().forEach { (a, b) ->
            val ax = a.longitude * metresPerDegreeLng
            val ay = a.latitude * metresPerDegreeLat
            val bx = b.longitude * metresPerDegreeLng
            val by = b.latitude * metresPerDegreeLat
            minimum = min(minimum, distancePointToSegment(px, py, ax, ay, bx, by))
        }
        return minimum
    }

    fun isOffRoute(
        latitude: Double,
        longitude: Double,
        route: EvacuationRoute,
        graph: RouteGraph,
        thresholdMeters: Double = OFF_ROUTE_THRESHOLD_METERS,
    ): Boolean = distanceToRouteMeters(latitude, longitude, route, graph) > thresholdMeters

    /** Standard point-to-segment distance in a plane. */
    private fun distancePointToSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) {
            return hypot(px - ax, py - ay)
        }
        // Clamp the projection parameter into [0, 1].
        val t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / lengthSquared))
        val cx = ax + t * dx
        val cy = ay + t * dy
        return hypot(px - cx, py - cy)
    }

    private fun hypot(x: Double, y: Double): Double = Math.hypot(x, y)
}

package com.example.georescux.domain.routing

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the graph node nearest to a geographic position (Haversine).
 * Pure Kotlin; deterministic — equal distances resolve by node id.
 */
object NearestNode {

    fun find(graph: RouteGraph, latitude: Double, longitude: Double): RouteNode? {
        if (graph.nodes.isEmpty()) return null
        return graph.nodes.minWithOrNull(
            compareBy(
                { node -> haversineMeters(latitude, longitude, node.latitude, node.longitude) },
                { node -> node.id },
            )
        )
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val halfChord = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * earthRadiusMeters * asin(sqrt(halfChord))
    }
}

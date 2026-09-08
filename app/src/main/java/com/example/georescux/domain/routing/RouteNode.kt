package com.example.georescux.domain.routing

/**
 * A node of the offline evacuation road graph: an intersection or a
 * destination. Safe havens are valid evacuation endpoints.
 */
data class RouteNode(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val isSafeHaven: Boolean = false,
)

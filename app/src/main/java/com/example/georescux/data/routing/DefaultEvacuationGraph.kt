package com.example.georescux.data.routing

import com.example.georescux.domain.routing.RouteEdge
import com.example.georescux.domain.routing.RouteGraph
import com.example.georescux.domain.routing.RouteNode
import com.example.georescux.domain.routing.RoadHazard

/**
 * Small SYNTHETIC evacuation graph used to seed local storage when no real
 * map region is bundled. Nodes and distances are illustrative sample data
 * for development — they are not real OSM road data and must not be
 * presented as such. Version 0 marks it as synthetic.
 *
 * Layout (roughly a straight corridor with two safe havens at the ends):
 * node-a - node-b - node-c - haven-1, plus node-a - node-d - haven-2
 * and a long bypass node-b - node-d.
 */
object DefaultEvacuationGraph {

    fun create(): RouteGraph = RouteGraph(
        nodes = listOf(
            RouteNode("node-a", latitude = 52.5100, longitude = 13.4000),
            RouteNode("node-b", latitude = 52.5150, longitude = 13.4050),
            RouteNode("node-c", latitude = 52.5200, longitude = 13.4100),
            RouteNode("haven-1", latitude = 52.5250, longitude = 13.4150, isSafeHaven = true),
            RouteNode("node-d", latitude = 52.5050, longitude = 13.3950),
            RouteNode("haven-2", latitude = 52.5000, longitude = 13.3900, isSafeHaven = true),
        ),
        edges = listOf(
            RouteEdge("node-a", "node-b", distanceMeters = 700.0),
            RouteEdge("node-b", "node-c", distanceMeters = 700.0),
            RouteEdge("node-c", "haven-1", distanceMeters = 700.0),
            RouteEdge("node-a", "node-d", distanceMeters = 800.0),
            RouteEdge("node-b", "node-d", distanceMeters = 1400.0),
            RouteEdge("node-d", "haven-2", distanceMeters = 900.0),
        ),
    )
}

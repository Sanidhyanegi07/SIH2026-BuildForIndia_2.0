package com.example.georescux.data.maps

/**
 * Offline place index for the Safe Route screen: named OSM places
 * (cities, towns, villages, hamlets) and facilities, each pre-snapped to
 * its routing-graph node. Produced by tools/uttarakhand/BuildRegionGraph.java
 * as `places.json`.
 *
 * Enables "type a place name" start/destination entry that resolves fully
 * offline — no Geocoder, no network.
 */
data class PlaceEntry(
    val name: String,
    val kind: String,
    val latitude: Double,
    val longitude: Double,
    val nodeId: String,
    val isSafeHaven: Boolean,
)

object PlaceIndex {

    fun parse(json: String): List<PlaceEntry> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        val places = root["places"] as? List<*> ?: return emptyList()
        return places.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"] as? String ?: return@mapNotNull null
            val nodeId = map["nodeId"] as? String ?: return@mapNotNull null
            val latitude = map["latitude"] as? Double ?: return@mapNotNull null
            val longitude = map["longitude"] as? Double ?: return@mapNotNull null
            PlaceEntry(
                name = name,
                kind = map["kind"] as? String ?: "",
                latitude = latitude,
                longitude = longitude,
                nodeId = nodeId,
                isSafeHaven = map["isSafeHaven"] as? Boolean ?: false,
            )
        }
    }

    fun loadFromAsset(assets: android.content.res.AssetManager, path: String): List<PlaceEntry> = try {
        assets.open(path).use { stream -> parse(stream.bufferedReader().readText()) }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Resolves a typed query to a place: exact (case/diacritic-insensitive)
     * name match first, then unique prefix match, then any contains match.
     * Deterministic — ties resolve by name then nodeId.
     */
    fun find(places: List<PlaceEntry>, query: String): PlaceEntry? {
        val q = fold(query).trim()
        if (q.isEmpty()) return null
        val sorted = places.sortedWith(compareBy({ fold(it.name) }, { it.nodeId }))
        sorted.firstOrNull { fold(it.name) == q }?.let { return it }
        val prefixMatches = sorted.filter { fold(it.name).startsWith(q) }
        if (prefixMatches.size == 1) return prefixMatches.first()
        // Prefix match on the first word ("dehradun" matching "Dehradun City")
        sorted.firstOrNull { entry -> fold(entry.name).startsWith(q) }?.let { return it }
        return sorted.firstOrNull { fold(it.name).contains(q) }
    }

    /** Suggestions for autocomplete: prefix matches first, then contains. */
    fun suggestions(places: List<PlaceEntry>, query: String, limit: Int = 8): List<String> {
        val q = fold(query).trim()
        if (q.isEmpty()) return emptyList()
        val prefix = mutableListOf<String>()
        val contains = mutableListOf<String>()
        for (place in places) {
            val folded = fold(place.name)
            if (folded.startsWith(q)) {
                if (place.name !in prefix) prefix.add(place.name)
            } else if (folded.contains(q)) {
                if (place.name !in contains) contains.add(place.name)
            }
            if (prefix.size >= limit) break
        }
        return (prefix + contains).distinct().take(limit)
    }

    /** Lowercase + strip diacritics ("Haridwār" matches "haridwar"). */
    fun fold(s: String): String = java.text.Normalizer.normalize(
        s.trim().lowercase(java.util.Locale.ROOT),
        java.text.Normalizer.Form.NFD,
    ).replace(Regex("\\p{M}+"), "")
}

package com.example.georescux.domain.admin

/**
 * A district inside a state: the finest geographic unit the admin portal
 * aggregates emergencies over (spec §11.2: India → State → District).
 *
 * [bounds] is a coarse lat/lng box used to classify an SOS position into a
 * district. District shapes are irregular, so a point near a border can
 * land in either neighbour; the box is a triage aid, not a cadastral
 * boundary. The raw SOS count remains the ground truth (spec §11.3).
 */
data class District(
    val id: String,
    val displayName: String,
    val stateId: String,
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in minLatitude..maxLatitude && longitude in minLongitude..maxLongitude
}

/**
 * District reference data for the states GeoRescuX ships. Coordinates are
 * coarse bounding boxes derived from public state/district geography; they
 * are accurate enough for aggregation, not for navigation.
 */
object DistrictCatalog {

    val uttarakhandDistricts = listOf(
        District("dehradun", "Dehradun", "uttarakhand", 30.20, 31.05, 77.45, 78.45),
        District("haridwar", "Haridwar", "uttarakhand", 29.55, 30.45, 77.45, 78.45),
        District("nainital", "Nainital", "uttarakhand", 29.00, 29.75, 78.75, 80.10),
        District("almora", "Almora", "uttarakhand", 29.25, 30.15, 79.25, 80.15),
        District("pithoragarh", "Pithoragarh", "uttarakhand", 29.25, 30.75, 79.55, 81.15),
        District("chamoli", "Chamoli", "uttarakhand", 30.15, 31.15, 78.95, 80.25),
        District("rudraprayag", "Rudraprayag", "uttarakhand", 29.95, 30.75, 78.75, 79.95),
        District("tehri_garhwal", "Tehri Garhwal", "uttarakhand", 29.95, 30.85, 77.95, 79.05),
        District("pauri_garhwal", "Pauri Garhwal", "uttarakhand", 29.65, 30.65, 78.15, 79.45),
        District("bageshwar", "Bageshwar", "uttarakhand", 29.55, 30.45, 79.45, 80.45),
        District("champawat", "Champawat", "uttarakhand", 28.85, 29.65, 79.45, 80.55),
        District("udham_singh_nagar", "Udham Singh Nagar", "uttarakhand", 28.75, 29.45, 78.85, 80.25),
        District("uttarkashi", "Uttarkashi", "uttarakhand", 30.45, 31.25, 77.85, 79.35),
    )

    val delhiDistricts = listOf(
        District("new_delhi", "New Delhi", "sample-region", 28.55, 28.70, 77.15, 77.30),
        District("central_delhi", "Central Delhi", "sample-region", 28.62, 28.70, 77.18, 77.26),
    )

    /** Every district of every shipped state. */
    val allDistricts: List<District> = uttarakhandDistricts + delhiDistricts

    fun districtsForState(stateId: String): List<District> =
        allDistricts.filter { it.stateId == stateId }

    /** The district containing the position, or null when it lies outside the shipped districts. */
    fun districtForLocation(latitude: Double, longitude: Double): District? =
        allDistricts.firstOrNull { it.contains(latitude, longitude) }
}

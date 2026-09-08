package com.example.georescux.data.maps.ingestion

data class MapManifest(
    val regionId: String,
    val displayName: String,
    val packageType: String = "regional_map",
    val sourceFormat: String = "osm.pbf",
    val sourceProvider: String = "OpenStreetMap",
    val fileName: String = "state.osm.pbf",
    val fileSize: Long,
    val sha256Checksum: String,
    val packageVersion: Int = 1,
    val generationTimestamp: Long = System.currentTimeMillis(),
    val validationStatus: String = "VALIDATED"
) {
    fun toJson(): String {
        return """
            {
              "regionId": "$regionId",
              "displayName": "$displayName",
              "packageType": "$packageType",
              "sourceFormat": "$sourceFormat",
              "sourceProvider": "$sourceProvider",
              "fileName": "$fileName",
              "fileSize": $fileSize,
              "sha256Checksum": "$sha256Checksum",
              "packageVersion": $packageVersion,
              "generationTimestamp": $generationTimestamp,
              "validationStatus": "$validationStatus"
            }
        """.trimIndent()
    }
}

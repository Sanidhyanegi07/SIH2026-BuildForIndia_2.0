
package com.example.georescux.data.maps.ingestion

data class MapManifest(
    val regionId: String,
    val displayName: String,
    val packageType: String = "regional_map",
    val sourceFormat: String = "OSM PBF",
    val sourceProvider: String = "OpenStreetMap",
    val sourceFile: String = "state.osm.pbf",
    val renderingFormat: String = "Mapsforge MAP",
    val renderingFile: String = "state.map",
    val fileSizeBytes: Long,
    val sha256: String,
    val packageVersion: Int = 1,
    val generatedAt: String,
    val validationStatus: String = "valid"
) {
    fun toJson(): String {
        return """
            {
              "regionId": "$regionId",
              "displayName": "$displayName",
              "packageType": "$packageType",
              "sourceFormat": "$sourceFormat",
              "sourceProvider": "$sourceProvider",
              "sourceFile": "$sourceFile",
              "renderingFormat": "$renderingFormat",
              "renderingFile": "$renderingFile",
              "fileSizeBytes": $fileSizeBytes,
              "sha256": "$sha256",
              "sha256Checksum": "$sha256",
              "packageVersion": $packageVersion,
              "generatedAt": "$generatedAt",
              "validationStatus": "$validationStatus"
            }
        """.trimIndent()
    }
}

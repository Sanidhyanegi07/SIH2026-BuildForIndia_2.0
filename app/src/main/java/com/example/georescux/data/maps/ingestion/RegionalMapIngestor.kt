package com.example.georescux.data.maps.ingestion

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class RegionalMapIngestor(
    private val sourceDir: File,
    private val outputDir: File
) {
    companion object {
        val SUPPORTED_REGIONS = mapOf(
            "uttarakhand" to "uttarakhand-latest.osm.pbf",
            "himachal_pradesh" to "himachal_pradesh-latest.osm.pbf",
            "haryana" to "haryana-latest.osm.pbf",
            "uttar_pradesh" to "uttar_pradesh-latest.osm.pbf"
        )
        
        val REGION_DISPLAY_NAMES = mapOf(
            "uttarakhand" to "Uttarakhand",
            "himachal_pradesh" to "Himachal Pradesh",
            "haryana" to "Haryana",
            "uttar_pradesh" to "Uttar Pradesh"
        )
        
        // Approximate bounding boxes for boundary geojson generation
        val REGION_BOUNDING_BOXES = mapOf(
            "uttarakhand" to listOf(
                listOf(77.5, 28.7), listOf(81.0, 28.7),
                listOf(81.0, 31.4), listOf(77.5, 31.4), listOf(77.5, 28.7)
            ),
            "himachal_pradesh" to listOf(
                listOf(75.5, 30.3), listOf(79.0, 30.3),
                listOf(79.0, 33.2), listOf(75.5, 33.2), listOf(75.5, 30.3)
            ),
            "haryana" to listOf(
                listOf(74.4, 27.6), listOf(77.6, 27.6),
                listOf(77.6, 30.9), listOf(74.4, 30.9), listOf(74.4, 27.6)
            ),
            "uttar_pradesh" to listOf(
                listOf(77.0, 23.8), listOf(84.6, 23.8),
                listOf(84.6, 30.4), listOf(77.0, 30.4), listOf(77.0, 23.8)
            )
        )
    }

    fun ingestAll() {
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            throw IllegalArgumentException("Source directory does not exist or is not a directory: ${sourceDir.absolutePath}")
        }
        
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        for ((regionId, filename) in SUPPORTED_REGIONS) {
            val sourceFile = File(sourceDir, filename)
            if (!sourceFile.exists()) {
                throw IllegalStateException("Missing source map for region $regionId: $filename")
            }
            prepareRegionPackage(regionId, sourceFile)
        }
    }

    fun prepareRegionPackage(regionId: String, sourceFile: File) {
        if (!sourceFile.exists() || !sourceFile.canRead() || sourceFile.length() == 0L) {
            throw IllegalArgumentException("Invalid source PBF file for region $regionId. Must exist, be readable, and non-empty.")
        }
        
        val regionDir = File(outputDir, regionId)
        if (!regionDir.exists()) {
            regionDir.mkdirs()
        }
        
        val targetPbf = File(regionDir, "state.osm.pbf")
        
        // Calculate SHA-256 for source
        val sourceSha256 = calculateSha256(sourceFile)
        
        // Idempotency check
        if (targetPbf.exists() && targetPbf.length() == sourceFile.length()) {
            val targetSha256 = calculateSha256(targetPbf)
            if (targetSha256 != sourceSha256) {
                // Checksum mismatch, copy again
                copyFileDeterministic(sourceFile, targetPbf)
            }
        } else {
            // Missing or size mismatch, copy
            copyFileDeterministic(sourceFile, targetPbf)
        }
        
        // Double check target after copy
        if (!targetPbf.exists() || targetPbf.length() == 0L) {
            throw IllegalStateException("Failed to create target PBF for region $regionId")
        }
        val finalSha256 = calculateSha256(targetPbf)
        if (finalSha256 != sourceSha256) {
            throw IllegalStateException("Checksum mismatch after copying PBF for region $regionId")
        }

        // Generate manifest
        val displayName = REGION_DISPLAY_NAMES[regionId] ?: regionId
        val manifest = MapManifest(
            regionId = regionId,
            displayName = displayName,
            fileSize = targetPbf.length(),
            sha256Checksum = finalSha256
        )
        val manifestFile = File(regionDir, "manifest.json")
        manifestFile.writeText(manifest.toJson())
        
        // Generate boundary
        val boundaryFile = File(regionDir, "boundary.geojson")
        val coords = REGION_BOUNDING_BOXES[regionId] ?: throw IllegalStateException("Missing bounding box for $regionId")
        boundaryFile.writeText(generateGeoJson(regionId, coords))
    }
    
    private fun copyFileDeterministic(source: File, target: File) {
        val tempTarget = File(target.parentFile, "${target.name}.tmp")
        try {
            Files.copy(source.toPath(), tempTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(tempTarget.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tempTarget.delete()
            throw IllegalStateException("Failed to copy source file ${source.name} safely", e)
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun generateGeoJson(regionId: String, coords: List<List<Double>>): String {
        val coordsString = coords.joinToString(",\n            ") { point ->
            "[${point[0]}, ${point[1]}]"
        }
        return """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {
                    "regionId": "$regionId"
                  },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [
                        $coordsString
                      ]
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }
}

fun main() {
    val projectRoot = File(System.getProperty("user.dir"))
    val sourceDir = File(projectRoot, "source")
    val outputDir = File(projectRoot, "output")
    
    println("Starting Regional Map Ingestion...")
    println("Source directory: ${sourceDir.absolutePath}")
    println("Output directory: ${outputDir.absolutePath}")
    
    val ingestor = RegionalMapIngestor(sourceDir, outputDir)
    try {
        ingestor.ingestAll()
        println("Ingestion completed successfully!")
    } catch (e: Exception) {
        println("Ingestion failed: ${e.message}")
        e.printStackTrace()
    }
}

package com.example.georescux.data.maps.ingestion

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class RegionalMapIngestorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sourceDir: File
    private lateinit var outputDir: File
    private lateinit var ingestor: RegionalMapIngestor

    @Before
    fun setup() {
        sourceDir = tempFolder.newFolder("source")
        outputDir = tempFolder.newFolder("output")
        ingestor = RegionalMapIngestor(sourceDir, outputDir)
    }

    private fun createValidPbf(filename: String): File {
        val file = File(sourceDir, filename)
        file.writeBytes("mock_pbf_data_$filename".toByteArray())
        return file
    }

    private fun setupAllValidSources() {
        RegionalMapIngestor.SUPPORTED_REGIONS.values.forEach { createValidPbf(it) }
    }

    @Test
    fun testAllFourRegionsDiscoveredAndIngested() {
        setupAllValidSources()
        ingestor.ingestAll()

        val regions = listOf("uttarakhand", "himachal_pradesh", "haryana", "uttar_pradesh")
        regions.forEach { regionId ->
            val regionDir = File(outputDir, regionId)
            assertTrue("Region directory missing: $regionId", regionDir.exists())
            
            val statePbf = File(regionDir, "state.osm.pbf")
            assertTrue("State PBF missing: $regionId", statePbf.exists())
            assertTrue("State PBF empty: $regionId", statePbf.length() > 0)
            
            val manifest = File(regionDir, "manifest.json")
            assertTrue("Manifest missing: $regionId", manifest.exists())
            val manifestContent = manifest.readText()
            assertTrue("Manifest should have regionId", manifestContent.contains("\"regionId\": \"$regionId\""))
            assertTrue("Manifest should have sourceFormat", manifestContent.contains("\"sourceFormat\": \"OSM PBF\""))
            assertTrue("Manifest should have renderingFormat", manifestContent.contains("\"renderingFormat\": \"Mapsforge MAP\""))
            assertTrue("Manifest should have fileSizeBytes", manifestContent.contains("\"fileSizeBytes\":"))
            assertTrue("Manifest should have sha256", manifestContent.contains("\"sha256\":"))
            assertTrue("Manifest should have generatedAt", manifestContent.contains("\"generatedAt\":"))
            
            val boundary = File(regionDir, "boundary.geojson")
            assertTrue("Boundary missing: $regionId", boundary.exists())
            val boundaryContent = boundary.readText()
            assertTrue(boundaryContent.contains("\"Polygon\""))
            assertTrue(boundaryContent.contains("\"regionId\": \"$regionId\""))
        }
    }

    @Test
    fun testMissingSourceMapThrowsException() {
        // Missing all maps
        try {
            ingestor.ingestAll()
            fail("Should throw exception for missing maps")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Missing source map"))
        }
    }

    @Test
    fun testInvalidSourceMapEmpty() {
        val file = File(sourceDir, "uttarakhand-latest.osm.pbf")
        file.createNewFile() // empty file

        try {
            ingestor.prepareRegionPackage("uttarakhand", file)
            fail("Should throw exception for empty map")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid source PBF"))
        }
    }

    @Test
    fun testChecksumGeneration() {
        val file = createValidPbf("uttarakhand-latest.osm.pbf")
        ingestor.prepareRegionPackage("uttarakhand", file)
        
        val manifest = File(File(outputDir, "uttarakhand"), "manifest.json")
        val content = manifest.readText()
        
        val digest = MessageDigest.getInstance("SHA-256")
        val expectedHash = digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        
        assertTrue("Manifest should contain correct checksum", content.contains("\"sha256Checksum\": \"$expectedHash\""))
    }

    @Test
    fun testIdempotentExecution() {
        setupAllValidSources()
        ingestor.ingestAll()
        
        val targetPbf = File(File(outputDir, "uttarakhand"), "state.osm.pbf")
        val lastModified = targetPbf.lastModified()
        
        // Run again
        ingestor.ingestAll()
        
        // Should not have modified the file since it's identical
        assertEquals(lastModified, targetPbf.lastModified())
    }

    @Test
    fun testCorruptedPackageOverwritten() {
        setupAllValidSources()
        ingestor.ingestAll()
        
        val targetPbf = File(File(outputDir, "uttarakhand"), "state.osm.pbf")
        targetPbf.writeBytes("corrupted_data".toByteArray()) // corrupt it
        
        // Run again
        ingestor.ingestAll()
        
        val expectedData = "mock_pbf_data_uttarakhand-latest.osm.pbf".toByteArray()
        assertArrayEquals(expectedData, targetPbf.readBytes())
    }

    @Test
    fun executeRealIngestion() {
        val projectRoot = File(System.getProperty("user.dir") ?: ".").parentFile
        val realSource = File(projectRoot, "source")
        val realOutput = File(projectRoot, "output")
        if (realSource.exists() && realSource.isDirectory) {
            println("Executing real ingestion from ${realSource.absolutePath} to ${realOutput.absolutePath}")
            val ingestor = RegionalMapIngestor(realSource, realOutput)
            ingestor.ingestAll()
        }
    }
}

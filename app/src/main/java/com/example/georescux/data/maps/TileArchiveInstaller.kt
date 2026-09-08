package com.example.georescux.data.maps

import android.content.Context
import com.example.georescux.domain.routing.MapRegion
import java.io.File

/**
 * Copies the bundled offline tile archive from assets into the osmdroid
 * base path (app-private storage), where osmdroid discovers it. Idempotent.
 */
object TileArchiveInstaller {

    fun ensureExtracted(context: Context, region: MapRegion): File? = try {
        val extension = region.tileAssetPath.substringAfterLast('.', "zip")
        val target = File(File(context.filesDir, "osmdroid"), "${region.id}-tiles.$extension")
        if (!target.exists()) {
            context.assets.open(region.tileAssetPath).use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target
    } catch (e: Exception) {
        null
    }
}

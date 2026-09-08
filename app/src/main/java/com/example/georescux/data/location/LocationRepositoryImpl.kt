package com.example.georescux.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.example.georescux.domain.repository.LocationRepository
import com.example.georescux.domain.sos.SosLocation
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Fused Location Provider implementation of [LocationRepository].
 * Uses only the application context (never an Activity), delivers fixes on
 * the main thread, and is fully stopped by [stopAcquisition].
 */
class LocationRepositoryImpl(context: Context) : LocationRepository {

    private val appContext: Context = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
    private var onLocation: ((SosLocation) -> Unit)? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { deliver(it) }
        }
    }

    // The ViewModel only starts acquisition after the permission check, so
    // a SecurityException here means the permission was revoked externally.
    @SuppressLint("MissingPermission")
    override fun startAcquisition(onLocation: (SosLocation) -> Unit) {
        this.onLocation = onLocation
        try {
            // Best available so far — often a recent fix, possibly null.
            fusedClient.lastLocation.addOnSuccessListener { location ->
                location?.let { deliver(it) }
            }

            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                .build()
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // No crash: the ViewModel keeps the emergency active and reports
            // the missing-permission state to the user.
        }
    }

    override fun stopAcquisition() {
        fusedClient.removeLocationUpdates(locationCallback)
        onLocation = null
    }

    private fun deliver(location: Location) {
        onLocation?.invoke(
            SosLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                timestampMs = location.time,
                provider = location.provider ?: "unknown",
            )
        )
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 60_000L
    }
}

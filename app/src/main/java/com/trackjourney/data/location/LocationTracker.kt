package com.trackjourney.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import com.trackjourney.data.model.TrackingSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
class LocationTracker(
    private val context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null

    /**
     * Emits location updates as a cold Flow with maximum GPS precision.
     * Uses PRIORITY_HIGH_ACCURACY and setGranularity(FINE) for best possible fix.
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(settings: TrackingSettings): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            settings.recordIntervalMs
        ).apply {
            setMinUpdateIntervalMillis(settings.recordIntervalMs / 2)
            setMinUpdateDistanceMeters(settings.minDistanceMeters)
            setWaitForAccurateLocation(true)
            setGranularity(Granularity.GRANULARITY_FINE)
            setMaxUpdateDelayMillis(0)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback!!,
            Looper.getMainLooper()
        )

        awaitClose {
            locationCallback?.let {
                fusedLocationClient.removeLocationUpdates(it)
            }
            locationCallback = null
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(): Location? {
        return try {
            fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            null
        }
    }

    fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
    }

    companion object {
        /**
         * Calculate distance between two GPS coordinates using the Haversine formula.
         */
        fun distanceBetween(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double
        ): Float {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0]
        }

        /**
         * Convert speed from m/s to km/h.
         */
        fun msToKmh(speedMs: Float): Float = speedMs * 3.6f
    }
}

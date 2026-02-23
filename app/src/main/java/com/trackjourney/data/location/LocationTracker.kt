package com.trackjourney.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import com.trackjourney.data.model.TrackingSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class LocationTracker(
    private val context: Context
) {
    companion object {
        private const val TAG = "LocationTracker"

        fun distanceBetween(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double
        ): Float {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0]
        }

        fun msToKmh(speedMs: Float): Float = speedMs * 3.6f
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    /**
     * Emits location updates as a cold Flow with maximum GPS precision.
     * Each call creates a new LocationRequest with the given settings.
     * When the flow is cancelled (e.g. by collectLatest on settings change),
     * the old location updates are removed and a new request can start.
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(settings: TrackingSettings): Flow<Location> = callbackFlow {
        Log.i(TAG, "Starting location updates: interval=${settings.recordIntervalMs}ms, minDist=${settings.minDistanceMeters}m")

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            settings.recordIntervalMs
        ).apply {
            setMinUpdateIntervalMillis(settings.recordIntervalMs / 2)
            setMinUpdateDistanceMeters(settings.minDistanceMeters)
            setWaitForAccurateLocation(true)
            setGranularity(Granularity.GRANULARITY_FINE)
            setMaxUpdateDelayMillis(settings.recordIntervalMs / 2)
        }.build()

        // Use a LOCAL callback — avoids race condition when collectLatest
        // cancels this flow and immediately starts a new one
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            callback,
            Looper.getMainLooper()
        )

        awaitClose {
            Log.i(TAG, "Removing location updates (was interval=${settings.recordIntervalMs}ms)")
            fusedLocationClient.removeLocationUpdates(callback)
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
        // Cleanup is handled by callbackFlow's awaitClose
        Log.i(TAG, "stopTracking called")
    }
}

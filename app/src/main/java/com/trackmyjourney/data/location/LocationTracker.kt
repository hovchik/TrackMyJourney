package com.trackmyjourney.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import com.trackmyjourney.data.model.TrackingSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class LocationTracker(
    private val context: Context
) {
    companion object {
        private const val TAG = "LocationTracker"

        // Cross-validation: GPS speed can be at most this factor above displacement speed.
        // Catches GPS Doppler spikes where reported speed far exceeds actual movement.
        private const val CROSS_VALIDATION_FACTOR = 1.5f

        // Tighter cross-validation for vehicle speeds (>40 km/h) where GPS multipath
        // and Doppler errors are more impactful on absolute value
        private const val CROSS_VALIDATION_FACTOR_HIGH = 1.25f

        // Speed threshold above which tighter cross-validation applies (m/s ≈ 40 km/h)
        private const val HIGH_SPEED_CROSS_VALIDATION_THRESHOLD = 11.1f

        // EMA smoothing factor (0..1). Lower = smoother but laggier.
        private const val SPEED_EMA_ALPHA_LOW = 0.35f   // Walking/running: responsive
        private const val SPEED_EMA_ALPHA_HIGH = 0.15f   // Driving: smooth, rejects jitter

        // Max plausible acceleration at walking/running speeds (m/s²)
        private const val MAX_LOW_SPEED_ACCEL = 2.0f

        // Max plausible acceleration at vehicle speeds (m/s²)
        // Real-world vehicles rarely exceed 4.5 m/s² sustained
        private const val MAX_HIGH_SPEED_ACCEL = 4.5f

        // Threshold to switch between low/high accel limits (~20 km/h)
        private const val ACCEL_SPEED_THRESHOLD = 5.5f

        // GPS accuracy above which speed confidence is reduced (meters)
        private const val POOR_ACCURACY_THRESHOLD = 15f

        // Minimum time delta to compute meaningful speed (seconds)
        private const val MIN_TIME_DELTA_S = 0.5f

        fun distanceBetween(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double
        ): Float {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0]
        }

        fun msToKmh(speedMs: Float): Float = speedMs * 3.6f

        /**
         * Filters raw GPS speed by cross-validating against positional displacement,
         * limiting acceleration, applying EMA smoothing, and adjusting for GPS accuracy.
         *
         * @return Filtered speed in m/s
         */
        fun filterSpeed(
            gpsSpeedMs: Float,
            latitude: Double,
            longitude: Double,
            timestamp: Long,
            accuracyMeters: Float?,
            prevLat: Double?,
            prevLon: Double?,
            prevTimestamp: Long?,
            prevSpeedMs: Float?
        ): Float {
            // First point — no previous data to validate against
            if (prevLat == null || prevLon == null || prevTimestamp == null || prevSpeedMs == null) {
                return gpsSpeedMs
            }

            val timeDeltaS = (timestamp - prevTimestamp) / 1000.0f
            if (timeDeltaS < MIN_TIME_DELTA_S) return prevSpeedMs

            // 1. Speed from position displacement (bounded by actual GPS movement)
            val distM = distanceBetween(prevLat, prevLon, latitude, longitude)
            val calcSpeedMs = distM / timeDeltaS

            // 2. Cross-validate: GPS Doppler speed cannot exceed displacement-derived
            //    speed by more than the tolerance factor.
            //    Tighter at driving speeds to reject multipath/Doppler drift.
            val cvFactor = if (gpsSpeedMs > HIGH_SPEED_CROSS_VALIDATION_THRESHOLD) {
                CROSS_VALIDATION_FACTOR_HIGH
            } else {
                CROSS_VALIDATION_FACTOR
            }
            val crossValidated = minOf(gpsSpeedMs, calcSpeedMs * cvFactor)

            // 3. Acceleration limit: prevent physically impossible speed jumps.
            //    Tighter at walking/running pace, looser for vehicles.
            val maxAccel = if (prevSpeedMs < ACCEL_SPEED_THRESHOLD) {
                MAX_LOW_SPEED_ACCEL
            } else {
                MAX_HIGH_SPEED_ACCEL
            }
            val maxSpeedChange = maxAccel * timeDeltaS
            val accelLimited = crossValidated.coerceIn(
                (prevSpeedMs - maxSpeedChange).coerceAtLeast(0f),
                prevSpeedMs + maxSpeedChange
            )

            // 4. Speed-adaptive EMA: responsive at low speeds, smooth at driving speeds
            //    to reject GPS jitter on highways
            val emaAlpha = if (prevSpeedMs > HIGH_SPEED_CROSS_VALIDATION_THRESHOLD) {
                SPEED_EMA_ALPHA_HIGH
            } else {
                SPEED_EMA_ALPHA_LOW
            }
            val smoothed = emaAlpha * accelLimited + (1f - emaAlpha) * prevSpeedMs

            // 5. Accuracy penalty: degrade speed when GPS fix is poor
            val result = if (accuracyMeters != null && accuracyMeters > POOR_ACCURACY_THRESHOLD) {
                smoothed * (POOR_ACCURACY_THRESHOLD / accuracyMeters).coerceIn(0.3f, 1f)
            } else {
                smoothed
            }

            return result.coerceAtLeast(0f)
        }

        /**
         * Compute robust maximum speed using percentile ranking.
         * Rejects remaining GPS spike outliers by taking the 95th percentile
         * instead of the raw maximum.
         */
        fun percentileSpeed(speeds: List<Float>, percentile: Float = 0.95f): Float {
            if (speeds.isEmpty()) return 0f
            val sorted = speeds.sorted()
            val index = ((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }
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

    /**
     * Emits location updates with a dynamically adjustable interval.
     * Used by AI Battery Saver and Energy Efficiency modes where the interval
     * can change based on speed, charging state, etc.
     *
     * @param intervalMs The initial interval; the caller should cancel and restart
     *   the flow when the interval needs to change.
     * @param minDistanceMeters Minimum displacement between updates.
     * @param priority GPS priority level.
     */
    @SuppressLint("MissingPermission")
    fun locationUpdatesWithInterval(
        intervalMs: Long,
        minDistanceMeters: Float,
        priority: Int = Priority.PRIORITY_HIGH_ACCURACY
    ): Flow<Location> = callbackFlow {
        Log.i(TAG, "Starting dynamic location updates: interval=${intervalMs}ms, minDist=${minDistanceMeters}m")

        val request = LocationRequest.Builder(priority, intervalMs).apply {
            setMinUpdateIntervalMillis(intervalMs / 2)
            setMinUpdateDistanceMeters(minDistanceMeters)
            setWaitForAccurateLocation(priority == Priority.PRIORITY_HIGH_ACCURACY)
            setGranularity(
                if (priority == Priority.PRIORITY_HIGH_ACCURACY) Granularity.GRANULARITY_FINE
                else Granularity.GRANULARITY_PERMISSION_LEVEL
            )
            setMaxUpdateDelayMillis(intervalMs / 2)
        }.build()

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
            Log.i(TAG, "Removing dynamic location updates (was interval=${intervalMs}ms)")
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

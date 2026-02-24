package com.trackjourney.data.location

import android.util.Log
import com.trackjourney.data.model.TrackingMode
import com.trackjourney.data.model.TrackingSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * AI-based GPS interval manager that dynamically adjusts the recording interval
 * based on tracking mode, charging state, and current speed.
 *
 * Modes:
 *  - HIGH_ACCURACY:      Uses the user's configured interval (1–30s), ignores AI.
 *  - ENERGY_EFFICIENCY:  Fixed 10s interval, balanced accuracy mode.
 *  - AI_BATTERY_SAVER:   Dynamic interval computed per-update:
 *      • Charging detected → 3s (maximum accuracy while plugged in)
 *      • Highway (>80 km/h) → 15s (predictable straight-line motion)
 *      • City driving (35–80 km/h) → 5s
 *      • Cycling (15–35 km/h) → 4s
 *      • Walking/running (<15 km/h) → 3s (need precision for turns)
 *      • Stationary (<1 km/h) → 10s (save battery, no useful data)
 */
class SmartIntervalManager(
    private val batteryMonitor: BatteryMonitor
) {
    companion object {
        private const val TAG = "SmartIntervalManager"

        // AI Battery Saver intervals (ms)
        private const val INTERVAL_CHARGING = 3000L
        private const val INTERVAL_HIGHWAY = 15000L
        private const val INTERVAL_CITY = 5000L
        private const val INTERVAL_CYCLING = 4000L
        private const val INTERVAL_WALKING = 3000L
        private const val INTERVAL_STATIONARY = 10000L

        // Energy Efficiency fixed interval
        private const val INTERVAL_ENERGY_EFFICIENCY = 10000L

        // Speed thresholds (km/h)
        private const val HIGHWAY_SPEED = 80f
        private const val CITY_SPEED = 35f
        private const val CYCLING_SPEED = 15f
        private const val STATIONARY_SPEED = 1f
    }

    private var lastComputedInterval = 3000L
    private var lastSpeedKmh = 0f

    /**
     * Compute the optimal GPS interval given current conditions.
     * Called after each location update to potentially adjust the next interval.
     */
    fun computeInterval(settings: TrackingSettings, currentSpeedKmh: Float): Long {
        lastSpeedKmh = currentSpeedKmh

        val interval = when (settings.trackingMode) {
            TrackingMode.HIGH_ACCURACY -> settings.recordIntervalMs

            TrackingMode.ENERGY_EFFICIENCY -> INTERVAL_ENERGY_EFFICIENCY

            TrackingMode.AI_BATTERY_SAVER -> {
                val isCharging = batteryMonitor.batteryState.value.isCharging
                if (isCharging) {
                    INTERVAL_CHARGING
                } else {
                    when {
                        currentSpeedKmh > HIGHWAY_SPEED -> INTERVAL_HIGHWAY
                        currentSpeedKmh > CITY_SPEED -> INTERVAL_CITY
                        currentSpeedKmh > CYCLING_SPEED -> INTERVAL_CYCLING
                        currentSpeedKmh > STATIONARY_SPEED -> INTERVAL_WALKING
                        else -> INTERVAL_STATIONARY
                    }
                }
            }
        }

        if (interval != lastComputedInterval) {
            Log.i(TAG, "Interval changed: ${lastComputedInterval}ms → ${interval}ms " +
                    "(mode=${settings.trackingMode}, speed=${String.format("%.1f", currentSpeedKmh)}km/h, " +
                    "charging=${batteryMonitor.batteryState.value.isCharging})")
            lastComputedInterval = interval
        }

        return interval
    }

    /**
     * Get the initial interval when tracking starts (before any speed data).
     */
    fun getInitialInterval(settings: TrackingSettings): Long {
        return when (settings.trackingMode) {
            TrackingMode.HIGH_ACCURACY -> settings.recordIntervalMs
            TrackingMode.ENERGY_EFFICIENCY -> INTERVAL_ENERGY_EFFICIENCY
            TrackingMode.AI_BATTERY_SAVER -> {
                if (batteryMonitor.batteryState.value.isCharging) INTERVAL_CHARGING
                else INTERVAL_WALKING // Default to responsive until speed is known
            }
        }
    }
}

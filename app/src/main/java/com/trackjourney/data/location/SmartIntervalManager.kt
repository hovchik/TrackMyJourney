package com.trackjourney.data.location

import android.util.Log
import com.trackjourney.data.model.TrackingMode
import com.trackjourney.data.model.TrackingSettings

/**
 * AI-based GPS interval manager that dynamically adjusts the recording interval
 * based on tracking mode, charging state, current speed, **and physical motion
 * detected by the device sensors**.
 *
 * Modes:
 *  - HIGH_ACCURACY:      Uses the user's configured interval (1–30s), ignores AI.
 *  - ENERGY_EFFICIENCY:  Fixed 26s interval, balanced accuracy mode.
 *  - AI_BATTERY_SAVER:   Dynamic interval computed per-update:
 *      • Charging detected       → 3s   (maximum accuracy while plugged in)
 *      • Highway (>80 km/h)      → 15s  (predictable straight-line motion)
 *      • City driving (35–80)    → 5s
 *      • Cycling (15–35)         → 4s
 *      • Walking/running (<15)   → 3s   (need precision for turns)
 *      • Stationary (<1) + no sensor motion → GPS suspended (returns [GPS_SUSPENDED])
 *      • Stationary (<1) + sensor motion    → 10s (starting to move, keep checking)
 */
class SmartIntervalManager(
    private val batteryMonitor: BatteryMonitor,
    private val motionSensorManager: MotionSensorManager
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
        private const val INTERVAL_ENERGY_EFFICIENCY = 26000L

        // Speed thresholds (km/h)
        private const val HIGHWAY_SPEED = 80f
        private const val CITY_SPEED = 35f
        private const val CYCLING_SPEED = 15f
        private const val STATIONARY_SPEED = 1f

        /**
         * Sentinel value indicating GPS should be suspended entirely.
         * The service checks for this and stops requesting location updates
         * until the motion sensor signals movement again.
         */
        const val GPS_SUSPENDED = -1L
    }

    private var lastComputedInterval = 3000L

    /**
     * Compute the optimal GPS interval given current conditions.
     * Called after each location update to potentially adjust the next interval.
     *
     * @return interval in ms, or [GPS_SUSPENDED] when sensors show no motion
     *         and GPS speed is also stationary.
     */
    fun computeInterval(settings: TrackingSettings, currentSpeedKmh: Float): Long {
        val interval = when (settings.trackingMode) {
            TrackingMode.HIGH_ACCURACY -> settings.recordIntervalMs

            TrackingMode.ENERGY_EFFICIENCY -> INTERVAL_ENERGY_EFFICIENCY

            TrackingMode.AI_BATTERY_SAVER -> {
                val isCharging = batteryMonitor.batteryState.value.isCharging
                val motion = motionSensorManager.motionState.value

                if (isCharging) {
                    INTERVAL_CHARGING
                } else {
                    when {
                        currentSpeedKmh > HIGHWAY_SPEED -> INTERVAL_HIGHWAY
                        currentSpeedKmh > CITY_SPEED -> INTERVAL_CITY
                        currentSpeedKmh > CYCLING_SPEED -> INTERVAL_CYCLING
                        currentSpeedKmh > STATIONARY_SPEED -> INTERVAL_WALKING
                        else -> {
                            // GPS says stationary — consult physical sensors
                            if (motion.gpsNeeded) {
                                // Sensors detect real motion (or lingering after motion)
                                INTERVAL_STATIONARY
                            } else {
                                // Truly stationary — suspend GPS to save battery
                                GPS_SUSPENDED
                            }
                        }
                    }
                }
            }
        }

        if (interval != lastComputedInterval) {
            val intervalStr = if (interval == GPS_SUSPENDED) "SUSPENDED" else "${interval}ms"
            Log.i(TAG, "Interval changed: ${formatInterval(lastComputedInterval)} → $intervalStr " +
                    "(mode=${settings.trackingMode}, speed=${String.format("%.1f", currentSpeedKmh)}km/h, " +
                    "charging=${batteryMonitor.batteryState.value.isCharging}, " +
                    "sensorMotion=${motionSensorManager.motionState.value.isDeviceMoving})")
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

    private fun formatInterval(ms: Long): String =
        if (ms == GPS_SUSPENDED) "SUSPENDED" else "${ms}ms"
}

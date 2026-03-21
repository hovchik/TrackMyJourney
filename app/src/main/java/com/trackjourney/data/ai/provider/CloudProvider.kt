package com.trackjourney.data.ai.provider

import android.util.Log
import com.trackjourney.BuildConfig
import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.model.TrackWithPoints
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud-based AI provider that sends summarized track data to a cloud API
 * (e.g., Claude, GPT) for analysis.
 */
@Singleton
class CloudProvider @Inject constructor() : AiAnalysisProvider {

    companion object {
        private const val TAG = "CloudProvider"
    }

    override val executionMode: AiExecutionMode = AiExecutionMode.CLOUD
    override val displayName: String = "Cloud AI"

    // API key would be stored securely and injected
    private var apiKey: String? = null

    fun setApiKey(key: String) {
        apiKey = key
    }

    override fun isConfigured(): Boolean = !apiKey.isNullOrBlank()

    override fun isAvailable(): Boolean = isConfigured()

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints): String {
        if (!isConfigured()) {
            throw IllegalStateException("Cloud AI provider not configured. Set API key first.")
        }
        val prompt = buildDailyPrompt(snapshot)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Prompt [daily]:\n$prompt")
        // TODO: Replace with actual cloud API call (Claude/GPT/Gemini)
        // For now, return the prompt wrapped as a structured placeholder
        val response = """{"source": "cloud_ai", "prompt": ${prompt.toJsonString()}, "status": "api_call_pending"}"""
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Response [daily]:\n$response")
        return response
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        if (!isConfigured()) {
            throw IllegalStateException("Cloud AI provider not configured. Set API key first.")
        }
        val prompt = buildWeeklyPrompt(snapshots)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Prompt [weekly]:\n$prompt")
        val response = """{"source": "cloud_ai", "prompt": ${prompt.toJsonString()}, "status": "api_call_pending"}"""
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Response [weekly]:\n$response")
        return response
    }

    private fun buildDailyPrompt(snapshot: TrackWithPoints): String {
        val track = snapshot.track
        val points = snapshot.points
        val healthData = snapshot.healthData

        val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
        val durationMin = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val durationSec = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60

        // Elevation stats from GPS points
        val altitudes = points.mapNotNull { it.altitude }
        val elevationGain = computeElevationGain(altitudes)
        val minAltitude = altitudes.minOrNull()
        val maxAltitude = altitudes.maxOrNull()

        // Heart rate stats from track points and health data
        val heartRates = points.mapNotNull { it.heartRate } +
                healthData.mapNotNull { it.heartRate }
        val avgHr = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val maxHr = heartRates.maxOrNull()
        val minHr = heartRates.minOrNull()

        // Cadence stats
        val cadences = points.mapNotNull { it.cadence }
        val avgCadence = cadences.takeIf { it.isNotEmpty() }?.average()?.toInt()

        // Speed segments for pace analysis
        val speeds = points.map { it.speedKmh }.filter { it > 0 }
        val medianSpeed = speeds.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 0f }

        // Place names
        val placeNames = points.mapNotNull { it.placeName }.distinct()

        // Battery usage
        val batteryDrain = if (track.batteryStart != null && track.batteryEnd != null)
            track.batteryStart - track.batteryEnd else null

        // Activity segments from points
        val activitySegments = points.groupBy { it.activityType }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        // Pace calculation (min/km) for walking/running
        val paceMinPerKm = if (track.distanceMeters > 0 && durationMin > 0) {
            (durationMin + durationSec / 60.0) / (track.distanceMeters / 1000.0)
        } else null

        // Moving vs stopped time estimation
        val stoppedPoints = points.count { it.speedKmh < 0.5f }
        val movingRatio = if (points.isNotEmpty()) {
            ((points.size - stoppedPoints).toFloat() / points.size * 100).toInt()
        } else 100

        // Speed consistency (coefficient of variation)
        val speedStdDev = if (speeds.size > 1) {
            val mean = speeds.average()
            sqrt(speeds.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0f
        val speedConsistency = if (speeds.isNotEmpty() && speeds.average() > 0) {
            (1.0 - (speedStdDev / speeds.average())).coerceIn(0.0, 1.0)
        } else 0.0

        return buildString {
            appendLine("You are a sports scientist and mobility analyst specializing in GPS-tracked activity data.")
            appendLine("Analyze this journey and return a JSON object with exactly these keys:")
            appendLine("- activity (string): the primary activity type — one of WALKING, RUNNING, CYCLING, DRIVING, FLYING, STATIONARY, or UNKNOWN")
            appendLine("- confidence (number 0.0-1.0): how confident you are in the activity classification based on the speed, duration, and movement patterns")
            appendLine("- summary (string): a 2-3 sentence natural-language summary of the journey covering distance, performance, and notable patterns. Be specific with numbers.")
            appendLine("- suggestions (array of strings): 2-4 specific, actionable suggestions. Reference the actual data (e.g., 'Your pace of X min/km could improve by...'). Avoid generic advice.")
            appendLine("- healthInsights (string or null): if heart rate or cadence data is present, provide specific health zone analysis (e.g., time in fat-burn vs cardio zone). Null if no health data.")
            appendLine()
            appendLine("=== JOURNEY DATA ===")
            appendLine("Detected Activity Type: ${track.activityType}")
            appendLine("Distance: ${"%.1f".format(track.distanceMeters)} meters (${"%.2f".format(track.distanceMeters / 1000)} km)")
            appendLine("Duration: ${durationMin}m ${durationSec}s")
            appendLine("Average Speed: ${"%.1f".format(track.avgSpeedKmh)} km/h")
            appendLine("Max Speed: ${"%.1f".format(track.maxSpeedKmh)} km/h")
            appendLine("Median Speed: ${"%.1f".format(medianSpeed)} km/h")
            if (paceMinPerKm != null && track.avgSpeedKmh < 20) {
                appendLine("Pace: ${"%.1f".format(paceMinPerKm)} min/km")
            }
            appendLine("Speed Consistency: ${"%.0f".format(speedConsistency * 100)}% (higher = steadier pace)")
            appendLine("Moving Time Ratio: $movingRatio% (${points.size - stoppedPoints} of ${points.size} GPS points in motion)")
            appendLine("Calories Burned: ${"%.0f".format(track.caloriesBurned)} kcal")

            if (minAltitude != null && maxAltitude != null) {
                appendLine()
                appendLine("=== ELEVATION ===")
                appendLine("Min Altitude: ${"%.1f".format(minAltitude)} m")
                appendLine("Max Altitude: ${"%.1f".format(maxAltitude)} m")
                appendLine("Elevation Gain: ${"%.1f".format(elevationGain)} m")
                appendLine("Elevation Range: ${"%.1f".format(maxAltitude - minAltitude)} m")
            }

            if (avgHr != null) {
                appendLine()
                appendLine("=== HEART RATE ===")
                appendLine("Average HR: $avgHr bpm")
                appendLine("Max HR: $maxHr bpm")
                appendLine("Min HR: $minHr bpm")
                // Heart rate zone distribution hints
                val hrAbove150 = heartRates.count { it > 150 }
                val hrBelow100 = heartRates.count { it < 100 }
                val totalHr = heartRates.size
                if (totalHr > 0) {
                    appendLine("Time above 150 bpm: ${"%.0f".format(hrAbove150 * 100.0 / totalHr)}%")
                    appendLine("Time below 100 bpm: ${"%.0f".format(hrBelow100 * 100.0 / totalHr)}%")
                }
            }

            if (avgCadence != null) {
                appendLine()
                appendLine("=== CADENCE ===")
                appendLine("Average Cadence: $avgCadence rpm/spm")
            }

            if (placeNames.isNotEmpty()) {
                appendLine()
                appendLine("=== LOCATIONS ===")
                if (track.startPlaceName != null) appendLine("Start: ${track.startPlaceName}")
                if (track.endPlaceName != null) appendLine("End: ${track.endPlaceName}")
                appendLine("Places passed: ${placeNames.joinToString(", ")}")
            }

            if (batteryDrain != null) {
                appendLine()
                appendLine("=== DEVICE ===")
                appendLine("Battery Used: $batteryDrain%")
            }

            if (track.rideCost != null) {
                appendLine("Ride Cost: ${"%.2f".format(track.rideCost)}")
            }

            if (activitySegments.size > 1) {
                appendLine()
                appendLine("=== ACTIVITY SEGMENTS ===")
                activitySegments.forEach { (activity, count) ->
                    val pct = if (points.isNotEmpty()) (count * 100) / points.size else 0
                    appendLine("$activity: $pct% of track ($count points)")
                }
            }

            appendLine()
            appendLine("Important: Base your analysis on the actual numbers provided. Do not invent data. If the detected activity type seems inconsistent with the speed/distance data, note the discrepancy and suggest the correct activity. Return valid JSON only.")
        }
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        return buildString {
            appendLine("You are a sports scientist and mobility analyst. Analyze these ${snapshots.size} tracked journeys from the past week and identify patterns, trends, and actionable improvements.")
            appendLine()
            appendLine("Return a JSON object with exactly these keys:")
            appendLine("- totalDistance (number): total distance in meters across all journeys")
            appendLine("- totalCalories (number): total estimated calories burned")
            appendLine("- dominantActivity (string): the most frequent activity type this week")
            appendLine("- weekSummary (string): 3-4 sentence summary covering overall activity level, consistency (how many days were active vs rest), notable achievements or concerns, and comparison between best and weakest sessions")
            appendLine("- improvements (array of strings): 3-5 specific, data-backed improvement suggestions. Reference actual numbers from the data (e.g., 'Your Tuesday run averaged X km/h — try maintaining that pace on Thursday's shorter route'). Avoid generic advice like 'stay hydrated'.")
            appendLine("- healthTrend (string or null): if heart rate data is available across multiple sessions, describe the trend (improving, stable, declining). Null if insufficient data.")
            appendLine()

            var totalDistance = 0.0
            var totalCalories = 0.0
            var totalDurationMs = 0L
            val activitiesThisWeek = mutableListOf<String>()

            snapshots.forEachIndexed { i, twp ->
                val track = twp.track
                val points = twp.points
                val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
                totalDistance += track.distanceMeters
                totalCalories += track.caloriesBurned
                totalDurationMs += durationMs
                activitiesThisWeek.add(track.activityType.toString())

                val durationMin = TimeUnit.MILLISECONDS.toMinutes(durationMs)
                val altitudes = points.mapNotNull { it.altitude }
                val elevGain = computeElevationGain(altitudes)
                val heartRates = points.mapNotNull { it.heartRate } +
                        twp.healthData.mapNotNull { it.heartRate }
                val avgHr = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt()
                val maxHr = heartRates.maxOrNull()

                appendLine("--- Track ${i + 1} ---")
                appendLine("Activity: ${track.activityType}")
                appendLine("Distance: ${"%.1f".format(track.distanceMeters)}m (${"%.2f".format(track.distanceMeters / 1000)}km)")
                appendLine("Duration: ${durationMin} min")
                appendLine("Avg Speed: ${"%.1f".format(track.avgSpeedKmh)} km/h | Max: ${"%.1f".format(track.maxSpeedKmh)} km/h")
                appendLine("Calories: ${"%.0f".format(track.caloriesBurned)} kcal")
                if (elevGain > 0) appendLine("Elevation Gain: ${"%.0f".format(elevGain)} m")
                if (avgHr != null) appendLine("Heart Rate: avg $avgHr bpm, max $maxHr bpm")
                if (track.startPlaceName != null || track.endPlaceName != null) {
                    appendLine("Route: ${track.startPlaceName ?: "?"} → ${track.endPlaceName ?: "?"}")
                }
                appendLine()
            }

            val totalDurationMin = TimeUnit.MILLISECONDS.toMinutes(totalDurationMs)
            val avgDistPerTrack = totalDistance / snapshots.size
            val activityBreakdown = activitiesThisWeek.groupBy { it }.mapValues { it.value.size }

            appendLine("=== WEEKLY TOTALS ===")
            appendLine("Total Tracks: ${snapshots.size}")
            appendLine("Total Distance: ${"%.1f".format(totalDistance)}m (${"%.2f".format(totalDistance / 1000)}km)")
            appendLine("Avg Distance per Track: ${"%.2f".format(avgDistPerTrack / 1000)}km")
            appendLine("Total Duration: $totalDurationMin min")
            appendLine("Total Calories: ${"%.0f".format(totalCalories)} kcal")
            appendLine("Activity Breakdown: ${activityBreakdown.entries.joinToString { "${it.key}: ${it.value} sessions" }}")
            appendLine()
            appendLine("Important: Compare sessions against each other to identify the user's strongest and weakest performances. Suggest specific improvements based on the data, not generic wellness tips. Return valid JSON only.")
        }
    }

    private fun computeElevationGain(altitudes: List<Double>): Double {
        if (altitudes.size < 2) return 0.0
        var gain = 0.0
        for (i in 1 until altitudes.size) {
            val diff = altitudes[i] - altitudes[i - 1]
            if (diff > 0) gain += diff
        }
        return gain
    }

    private fun String.toJsonString(): String {
        return "\"" + this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
    }
}

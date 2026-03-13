package com.trackjourney.data.ai.provider

import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.model.TrackWithPoints
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud-based AI provider that sends summarized track data to a cloud API
 * (e.g., Claude, GPT) for analysis.
 */
@Singleton
class CloudProvider @Inject constructor() : AiAnalysisProvider {

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
        // TODO: Replace with actual cloud API call (Claude/GPT/Gemini)
        // For now, return the prompt wrapped as a structured placeholder
        return """{"source": "cloud_ai", "prompt": ${prompt.toJsonString()}, "status": "api_call_pending"}"""
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        if (!isConfigured()) {
            throw IllegalStateException("Cloud AI provider not configured. Set API key first.")
        }
        val prompt = buildWeeklyPrompt(snapshots)
        return """{"source": "cloud_ai", "prompt": ${prompt.toJsonString()}, "status": "api_call_pending"}"""
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

        return buildString {
            appendLine("You are an expert journey and fitness analyst. Analyze this tracked journey and provide detailed insights.")
            appendLine("Return a JSON object with these keys: activity (string), confidence (0.0-1.0), summary (string), suggestions (array of strings), healthInsights (string or null).")
            appendLine()
            appendLine("=== JOURNEY DATA ===")
            appendLine("Activity Type: ${track.activityType}")
            appendLine("Distance: ${"%.1f".format(track.distanceMeters)} meters (${"%.2f".format(track.distanceMeters / 1000)} km)")
            appendLine("Duration: ${durationMin}m ${durationSec}s")
            appendLine("Average Speed: ${"%.1f".format(track.avgSpeedKmh)} km/h")
            appendLine("Max Speed: ${"%.1f".format(track.maxSpeedKmh)} km/h")
            appendLine("Median Speed: ${"%.1f".format(medianSpeed)} km/h")
            appendLine("GPS Points: ${points.size}")
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
                    val pct = (count * 100) / points.size
                    appendLine("$activity: $pct% of track ($count points)")
                }
            }

            appendLine()
            appendLine("Provide actionable fitness and travel insights. Return valid JSON only.")
        }
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        return buildString {
            appendLine("You are an expert journey and fitness analyst. Analyze the following ${snapshots.size} journeys from the past week.")
            appendLine("Return a JSON object with keys: totalDistance (meters), totalCalories, dominantActivity (string), weekSummary (string), improvements (array of strings), healthTrend (string or null).")
            appendLine()

            var totalDistance = 0.0
            var totalCalories = 0.0
            var totalDurationMs = 0L

            snapshots.forEachIndexed { i, twp ->
                val track = twp.track
                val points = twp.points
                val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
                totalDistance += track.distanceMeters
                totalCalories += track.caloriesBurned
                totalDurationMs += durationMs

                val durationMin = TimeUnit.MILLISECONDS.toMinutes(durationMs)
                val altitudes = points.mapNotNull { it.altitude }
                val elevGain = computeElevationGain(altitudes)
                val heartRates = points.mapNotNull { it.heartRate } +
                        twp.healthData.mapNotNull { it.heartRate }
                val avgHr = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt()

                appendLine("--- Track ${i + 1} ---")
                appendLine("Activity: ${track.activityType}")
                appendLine("Distance: ${"%.1f".format(track.distanceMeters)}m (${"%.2f".format(track.distanceMeters / 1000)}km)")
                appendLine("Duration: ${durationMin} min")
                appendLine("Avg Speed: ${"%.1f".format(track.avgSpeedKmh)} km/h | Max: ${"%.1f".format(track.maxSpeedKmh)} km/h")
                appendLine("Calories: ${"%.0f".format(track.caloriesBurned)} kcal")
                if (elevGain > 0) appendLine("Elevation Gain: ${"%.0f".format(elevGain)} m")
                if (avgHr != null) appendLine("Avg Heart Rate: $avgHr bpm")
                if (track.startPlaceName != null || track.endPlaceName != null) {
                    appendLine("Route: ${track.startPlaceName ?: "?"} → ${track.endPlaceName ?: "?"}")
                }
                appendLine()
            }

            val totalDurationMin = TimeUnit.MILLISECONDS.toMinutes(totalDurationMs)
            appendLine("=== WEEKLY TOTALS ===")
            appendLine("Total Tracks: ${snapshots.size}")
            appendLine("Total Distance: ${"%.1f".format(totalDistance)}m (${"%.2f".format(totalDistance / 1000)}km)")
            appendLine("Total Duration: $totalDurationMin min")
            appendLine("Total Calories: ${"%.0f".format(totalCalories)} kcal")
            appendLine()
            appendLine("Provide weekly trends, comparisons between journeys, and improvement suggestions. Return valid JSON only.")
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

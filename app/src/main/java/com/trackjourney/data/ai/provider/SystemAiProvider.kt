package com.trackjourney.data.ai.provider

import android.util.Log
import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.runtime.SystemAiRuntimeAdapter
import com.trackjourney.data.model.TrackWithPoints
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemAiProvider @Inject constructor(
    private val systemRuntime: SystemAiRuntimeAdapter
) : AiAnalysisProvider {

    companion object {
        private const val TAG = "SystemAiProvider"
    }

    override val executionMode: AiExecutionMode = AiExecutionMode.SYSTEM_LOCAL
    override val displayName: String = "System AI (AICore)"

    override fun isConfigured(): Boolean = systemRuntime.isAvailable()
    override fun isAvailable(): Boolean = systemRuntime.isAvailable()

    fun getStatusMessage(): String = systemRuntime.getStatusMessage()

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints): String {
        val prompt = buildDailyPrompt(snapshot)
        Log.d(TAG, "AI Prompt [daily]:\n$prompt")
        val response = systemRuntime.runPrompt(prompt)
        Log.d(TAG, "AI Response [daily]:\n$response")
        return response
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        val prompt = buildWeeklyPrompt(snapshots)
        Log.d(TAG, "AI Prompt [weekly]:\n$prompt")
        val response = systemRuntime.runPrompt(prompt)
        Log.d(TAG, "AI Response [weekly]:\n$response")
        return response
    }

    private fun buildDailyPrompt(snapshot: TrackWithPoints): String {
        val track = snapshot.track
        val points = snapshot.points
        val healthData = snapshot.healthData

        val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
        val durationMin = TimeUnit.MILLISECONDS.toMinutes(durationMs)

        // Elevation
        val altitudes = points.mapNotNull { it.altitude }
        val elevationGain = computeElevationGain(altitudes)
        val minAlt = altitudes.minOrNull()
        val maxAlt = altitudes.maxOrNull()

        // Heart rate
        val heartRates = points.mapNotNull { it.heartRate } +
                healthData.mapNotNull { it.heartRate }
        val avgHr = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val maxHr = heartRates.maxOrNull()

        // Cadence
        val avgCadence = points.mapNotNull { it.cadence }
            .takeIf { it.isNotEmpty() }?.average()?.toInt()

        // Activity segments
        val activitySegments = points.groupBy { it.activityType }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        return buildString {
            appendLine("Analyze this journey track and provide insights as JSON with keys: activity, confidence, summary, suggestions (array), healthInsights.")
            appendLine()
            appendLine("Track Data:")
            appendLine("- Activity: ${track.activityType}")
            appendLine("- Distance: ${"%.1f".format(track.distanceMeters)}m (${"%.2f".format(track.distanceMeters / 1000)}km)")
            appendLine("- Duration: ${durationMin} min")
            appendLine("- Avg Speed: ${"%.1f".format(track.avgSpeedKmh)} km/h")
            appendLine("- Max Speed: ${"%.1f".format(track.maxSpeedKmh)} km/h")
            appendLine("- GPS Points: ${points.size}")
            appendLine("- Calories: ${"%.0f".format(track.caloriesBurned)} kcal")

            if (minAlt != null && maxAlt != null) {
                appendLine("- Elevation: ${"%.0f".format(minAlt)}-${"%.0f".format(maxAlt)}m, gain: ${"%.0f".format(elevationGain)}m")
            }
            if (avgHr != null) {
                appendLine("- Heart Rate: avg $avgHr, max $maxHr bpm")
            }
            if (avgCadence != null) {
                appendLine("- Avg Cadence: $avgCadence")
            }
            if (track.startPlaceName != null || track.endPlaceName != null) {
                appendLine("- Route: ${track.startPlaceName ?: "?"} → ${track.endPlaceName ?: "?"}")
            }
            if (activitySegments.size > 1) {
                appendLine("- Segments: ${activitySegments.joinToString { "${it.key}(${(it.value * 100) / points.size}%)" }}")
            }
            if (track.rideCost != null) {
                appendLine("- Ride Cost: ${"%.2f".format(track.rideCost)}")
            }

            appendLine()
            appendLine("Return valid JSON only.")
        }
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        return buildString {
            appendLine("Analyze these ${snapshots.size} journey tracks from the past week. Return JSON with keys: totalDistance, totalCalories, dominantActivity, weekSummary, improvements (array).")
            appendLine()

            var totalDist = 0.0
            var totalCal = 0.0

            snapshots.forEachIndexed { i, twp ->
                val track = twp.track
                val durationMin = TimeUnit.MILLISECONDS.toMinutes(
                    (track.endTime ?: System.currentTimeMillis()) - track.startTime
                )
                totalDist += track.distanceMeters
                totalCal += track.caloriesBurned

                val altitudes = twp.points.mapNotNull { it.altitude }
                val elevGain = computeElevationGain(altitudes)
                val heartRates = twp.points.mapNotNull { it.heartRate } +
                        twp.healthData.mapNotNull { it.heartRate }
                val avgHr = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt()

                append("${i + 1}. ${track.activityType}: ${"%.1f".format(track.distanceMeters)}m, ${durationMin}min, ${"%.1f".format(track.avgSpeedKmh)}km/h, ${"%.0f".format(track.caloriesBurned)}cal")
                if (elevGain > 0) append(", elev+${"%.0f".format(elevGain)}m")
                if (avgHr != null) append(", hr:${avgHr}bpm")
                if (track.startPlaceName != null) append(", from:${track.startPlaceName}")
                appendLine()
            }

            appendLine()
            appendLine("Totals: ${"%.1f".format(totalDist)}m distance, ${"%.0f".format(totalCal)} cal")
            appendLine()
            appendLine("Return valid JSON only.")
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
}

package com.trackjourney.data.ai.provider

import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.LocalModelManager
import com.trackjourney.data.ai.runtime.LiteRtRuntimeAdapter
import com.trackjourney.data.ai.runtime.LocalModelRuntime
import com.trackjourney.data.ai.runtime.MediaPipeLlmRuntimeAdapter
import com.trackjourney.data.model.TrackWithPoints
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomLocalModelProvider @Inject constructor(
    private val modelManager: LocalModelManager,
    private val mediaPipeRuntime: MediaPipeLlmRuntimeAdapter,
    private val liteRtRuntime: LiteRtRuntimeAdapter
) : AiAnalysisProvider {

    override val executionMode: AiExecutionMode = AiExecutionMode.CUSTOM_LOCAL
    override val displayName: String = "Local Model"

    override fun isConfigured(): Boolean = modelManager.getActiveModelSync() != null

    override fun isAvailable(): Boolean {
        if (!isConfigured()) return false
        val activeModel = modelManager.getActiveModelSync() ?: return false
        val runtime = resolveRuntime(activeModel.runtimeType)
        return runtime?.isAvailable() == true
    }

    private fun resolveRuntime(runtimeType: String): LocalModelRuntime? {
        return when (runtimeType) {
            "mediapipe_llm" -> mediaPipeRuntime
            "litert" -> liteRtRuntime
            else -> null
        }
    }

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints): String {
        val activeModel = modelManager.getActiveModelSync()
            ?: throw IllegalStateException("No active local model configured")
        val runtime = resolveRuntime(activeModel.runtimeType)
            ?: throw IllegalStateException("No runtime available for ${activeModel.runtimeType}")

        // Load model if needed
        if (!runtime.isAvailable() && activeModel.localPath != null) {
            when (runtime) {
                is MediaPipeLlmRuntimeAdapter -> runtime.loadModel(activeModel.localPath)
                is LiteRtRuntimeAdapter -> runtime.loadModel(activeModel.localPath)
            }
        }

        val prompt = buildDailyPrompt(snapshot)
        val rawOutput = runtime.runPrompt(prompt)
        return extractJson(rawOutput)
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        val activeModel = modelManager.getActiveModelSync()
            ?: throw IllegalStateException("No active local model configured")
        val runtime = resolveRuntime(activeModel.runtimeType)
            ?: throw IllegalStateException("No runtime available for ${activeModel.runtimeType}")

        if (!runtime.isAvailable() && activeModel.localPath != null) {
            when (runtime) {
                is MediaPipeLlmRuntimeAdapter -> runtime.loadModel(activeModel.localPath)
                is LiteRtRuntimeAdapter -> runtime.loadModel(activeModel.localPath)
            }
        }

        val prompt = buildWeeklyPrompt(snapshots)
        val rawOutput = runtime.runPrompt(prompt)
        return extractJson(rawOutput)
    }

    private fun buildDailyPrompt(snapshot: TrackWithPoints): String {
        val track = snapshot.track
        val points = snapshot.points
        val healthData = snapshot.healthData

        val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
        val durationMin = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val durationSec = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60

        // Elevation stats
        val altitudes = points.mapNotNull { it.altitude }
        val elevationGain = computeElevationGain(altitudes)
        val minAlt = altitudes.minOrNull()
        val maxAlt = altitudes.maxOrNull()

        // Heart rate from points + health data
        val heartRates = points.mapNotNull { it.heartRate } +
                healthData.mapNotNull { it.heartRate }
        val avgHr = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val maxHr = heartRates.maxOrNull()

        // Cadence
        val avgCadence = points.mapNotNull { it.cadence }
            .takeIf { it.isNotEmpty() }?.average()?.toInt()

        // Speed analysis
        val speeds = points.map { it.speedKmh }.filter { it > 0 }
        val medianSpeed = speeds.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 0f }

        // Activity segments
        val activitySegments = points.groupBy { it.activityType }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        // Battery
        val batteryDrain = if (track.batteryStart != null && track.batteryEnd != null)
            track.batteryStart - track.batteryEnd else null

        return buildString {
            appendLine("You are a journey analysis AI. Analyze this track data and return a JSON object with keys: activity (string), summary (string), suggestions (array of strings), confidence (0.0-1.0), healthInsights (string or null).")
            appendLine()
            appendLine("Track Data:")
            appendLine("- Activity: ${track.activityType}")
            appendLine("- Distance: ${"%.1f".format(track.distanceMeters)}m (${"%.2f".format(track.distanceMeters / 1000)}km)")
            appendLine("- Duration: ${durationMin}m ${durationSec}s")
            appendLine("- Avg Speed: ${"%.1f".format(track.avgSpeedKmh)} km/h")
            appendLine("- Max Speed: ${"%.1f".format(track.maxSpeedKmh)} km/h")
            appendLine("- Median Speed: ${"%.1f".format(medianSpeed)} km/h")
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
                appendLine("- Activity Segments: ${activitySegments.joinToString { "${it.key}(${(it.value * 100) / points.size}%)" }}")
            }
            if (batteryDrain != null) {
                appendLine("- Battery Used: $batteryDrain%")
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
            appendLine("You are a journey analysis AI. Analyze these ${snapshots.size} tracks from the past week. Return a JSON object with keys: totalDistance (number), totalCalories (number), dominantActivity (string), weekSummary (string), improvements (array of strings).")
            appendLine()

            var totalDist = 0.0
            var totalCal = 0.0
            var totalDurationMs = 0L

            snapshots.forEachIndexed { i, twp ->
                val track = twp.track
                val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
                val durationMin = TimeUnit.MILLISECONDS.toMinutes(durationMs)
                totalDist += track.distanceMeters
                totalCal += track.caloriesBurned
                totalDurationMs += durationMs

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

            val totalDurationMin = TimeUnit.MILLISECONDS.toMinutes(totalDurationMs)
            appendLine()
            appendLine("Totals: ${"%.2f".format(totalDist / 1000)}km, ${totalDurationMin}min, ${"%.0f".format(totalCal)}cal")
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

    private fun extractJson(output: String): String {
        // Try to extract JSON from output that may contain extra text
        val jsonStart = output.indexOf('{')
        val jsonEnd = output.lastIndexOf('}')
        return if (jsonStart >= 0 && jsonEnd > jsonStart) {
            output.substring(jsonStart, jsonEnd + 1)
        } else {
            output
        }
    }
}

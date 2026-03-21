package com.trackjourney.data.ai.provider

import android.util.Log
import com.trackjourney.BuildConfig
import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.LocalModelManager
import com.trackjourney.data.ai.runtime.LiteRtRuntimeAdapter
import com.trackjourney.data.ai.runtime.LocalModelRuntime
import com.trackjourney.data.ai.runtime.MediaPipeLlmRuntimeAdapter
import com.trackjourney.data.model.TrackWithPoints
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomLocalModelProvider @Inject constructor(
    private val modelManager: LocalModelManager,
    private val mediaPipeRuntime: MediaPipeLlmRuntimeAdapter,
    private val liteRtRuntime: LiteRtRuntimeAdapter
) : AiAnalysisProvider {

    companion object {
        private const val TAG = "CustomLocalModelProvider"
    }

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

    private fun ensureModelLoaded(runtime: LocalModelRuntime, runtimeType: String, localPath: String?) {
        if (!runtime.isAvailable()) {
            if (localPath == null) {
                throw IllegalStateException("Model needs loading but no local path is available for runtime: $runtimeType")
            }
            Log.i(TAG, "Loading model from: $localPath (runtime: $runtimeType)")
            when (runtime) {
                is MediaPipeLlmRuntimeAdapter -> runtime.loadModel(localPath)
                is LiteRtRuntimeAdapter -> runtime.loadModel(localPath)
            }
            if (!runtime.isAvailable()) {
                Log.e(TAG, "Model failed to load from: $localPath")
                throw IllegalStateException("Model failed to load from: $localPath")
            }
            Log.i(TAG, "Model loaded successfully")
        }
    }

    private fun validateJsonResponse(json: String, requiredKeys: List<String>): String {
        try {
            val jsonObj = JSONObject(json)
            val missingKeys = requiredKeys.filter { !jsonObj.has(it) }
            if (missingKeys.isNotEmpty()) {
                Log.w(TAG, "AI response missing keys: $missingKeys")
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI response is not valid JSON: ${e.message}")
            if (BuildConfig.DEBUG) Log.d(TAG, "Raw response: $json")
        }
        return json
    }

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints): String {
        val activeModel = modelManager.getActiveModelSync()
            ?: throw IllegalStateException("No active local model configured")
        val runtime = resolveRuntime(activeModel.runtimeType)
            ?: throw IllegalStateException("No runtime available for ${activeModel.runtimeType}")

        ensureModelLoaded(runtime, activeModel.runtimeType, activeModel.localPath)

        val prompt = buildDailyPrompt(snapshot)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Prompt [daily] (model=${activeModel.displayName}):\n$prompt")
        val rawOutput = runtime.runPrompt(prompt)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Response [daily] (model=${activeModel.displayName}):\n$rawOutput")
        val json = extractJson(rawOutput)
        return validateJsonResponse(json, listOf("activity", "confidence", "summary", "suggestions"))
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        val activeModel = modelManager.getActiveModelSync()
            ?: throw IllegalStateException("No active local model configured")
        val runtime = resolveRuntime(activeModel.runtimeType)
            ?: throw IllegalStateException("No runtime available for ${activeModel.runtimeType}")

        ensureModelLoaded(runtime, activeModel.runtimeType, activeModel.localPath)

        val prompt = buildWeeklyPrompt(snapshots)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Prompt [weekly] (model=${activeModel.displayName}):\n$prompt")
        val rawOutput = runtime.runPrompt(prompt)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Response [weekly] (model=${activeModel.displayName}):\n$rawOutput")
        val json = extractJson(rawOutput)
        return validateJsonResponse(json, listOf("totalDistance", "totalCalories", "dominantActivity", "weekSummary", "improvements"))
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

        // Pace for walking/running
        val paceMinPerKm = if (track.distanceMeters > 0 && durationMin > 0) {
            (durationMin + durationSec / 60.0) / (track.distanceMeters / 1000.0)
        } else null

        return buildString {
            appendLine("You are a fitness and journey analyst. Analyze this GPS-tracked activity and return a JSON object with these keys:")
            appendLine("- activity (string): primary activity — WALKING, RUNNING, CYCLING, DRIVING, FLYING, or STATIONARY")
            appendLine("- confidence (0.0-1.0): classification confidence based on speed and movement patterns")
            appendLine("- summary (string): 2-3 sentences summarizing the journey with specific numbers from the data")
            appendLine("- suggestions (array of strings): 2-3 actionable tips referencing the actual metrics below")
            appendLine("- healthInsights (string or null): heart rate zone analysis if HR data present, otherwise null")
            appendLine()
            appendLine("Track Data:")
            appendLine("- Activity: ${track.activityType}")
            appendLine("- Distance: ${"%.2f".format(track.distanceMeters / 1000)}km (${"%.0f".format(track.distanceMeters)}m)")
            appendLine("- Duration: ${durationMin}m ${durationSec}s")
            appendLine("- Avg Speed: ${"%.1f".format(track.avgSpeedKmh)} km/h")
            appendLine("- Max Speed: ${"%.1f".format(track.maxSpeedKmh)} km/h")
            appendLine("- Median Speed: ${"%.1f".format(medianSpeed)} km/h")
            if (paceMinPerKm != null && track.avgSpeedKmh < 20) {
                appendLine("- Pace: ${"%.1f".format(paceMinPerKm)} min/km")
            }
            appendLine("- Calories: ${"%.0f".format(track.caloriesBurned)} kcal")

            if (minAlt != null && maxAlt != null) {
                appendLine("- Elevation: ${"%.0f".format(minAlt)}-${"%.0f".format(maxAlt)}m, gain: ${"%.0f".format(elevationGain)}m")
            }
            if (avgHr != null) {
                appendLine("- Heart Rate: avg $avgHr bpm, max $maxHr bpm")
            }
            if (avgCadence != null) {
                appendLine("- Cadence: $avgCadence spm")
            }
            if (track.startPlaceName != null || track.endPlaceName != null) {
                appendLine("- Route: ${track.startPlaceName ?: "?"} → ${track.endPlaceName ?: "?"}")
            }
            if (activitySegments.size > 1) {
                appendLine("- Activity Segments: ${activitySegments.joinToString { "${it.key}(${if (points.isNotEmpty()) (it.value * 100) / points.size else 0}%)" }}")
            }
            if (batteryDrain != null) {
                appendLine("- Battery Used: $batteryDrain%")
            }
            if (track.rideCost != null) {
                appendLine("- Ride Cost: ${"%.2f".format(track.rideCost)}")
            }

            appendLine()
            appendLine("Use the actual numbers in your summary and suggestions. If the activity type seems wrong for the speed, flag it. Return valid JSON only.")
        }
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        return buildString {
            appendLine("Analyze ${snapshots.size} GPS-tracked journeys from this week. Compare sessions and find trends.")
            appendLine("Return JSON: totalDistance (meters), totalCalories (number), dominantActivity (string), weekSummary (3-4 sentences comparing sessions, noting best/weakest performance), improvements (3-4 specific suggestions referencing actual data).")
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

                append("${i + 1}. ${track.activityType}: ${"%.2f".format(track.distanceMeters / 1000)}km, ${durationMin}min, ${"%.1f".format(track.avgSpeedKmh)}km/h, ${"%.0f".format(track.caloriesBurned)}cal")
                if (elevGain > 0) append(", elev+${"%.0f".format(elevGain)}m")
                if (avgHr != null) append(", hr:${avgHr}bpm")
                if (track.startPlaceName != null) append(", from:${track.startPlaceName}")
                appendLine()
            }

            val totalDurationMin = TimeUnit.MILLISECONDS.toMinutes(totalDurationMs)
            appendLine()
            appendLine("Totals: ${"%.2f".format(totalDist / 1000)}km, ${totalDurationMin}min, ${"%.0f".format(totalCal)}cal, avg ${"%.2f".format(totalDist / 1000 / snapshots.size)}km/session")
            appendLine()
            appendLine("Compare best vs weakest session. Use actual numbers in suggestions. Return valid JSON only.")
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

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
        // Model is available if it's installed with a local path and has a compatible runtime
        // The runtime will be loaded on first inference via ensureModelLoaded()
        return activeModel.localPath != null &&
                activeModel.installState == com.trackjourney.data.ai.models.ModelInstallState.INSTALLED &&
                resolveRuntime(activeModel.runtimeType) != null
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

        // Time of day
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = track.startTime }
        val startHour = startCal.get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when (startHour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(track.startTime))

        // Elevation stats
        val altitudes = points.mapNotNull { it.altitude }
        val elevationGain = computeElevationGain(altitudes)
        val elevationLoss = computeElevationLoss(altitudes)
        val minAlt = altitudes.minOrNull()
        val maxAlt = altitudes.maxOrNull()

        // Heart rate from points + health data
        val heartRates = points.mapNotNull { it.heartRate } +
                healthData.mapNotNull { it.heartRate }
        val avgHr = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val maxHr = heartRates.maxOrNull()
        val minHr = heartRates.minOrNull()

        // Cadence
        val cadences = points.mapNotNull { it.cadence }
        val avgCadence = cadences.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val maxCadence = cadences.maxOrNull()

        // Speed analysis
        val speeds = points.map { it.speedKmh }.filter { it > 0 }
        val sortedSpeeds = speeds.sorted()
        val medianSpeed = if (sortedSpeeds.isNotEmpty()) sortedSpeeds[sortedSpeeds.size / 2] else 0f
        val p10Speed = if (sortedSpeeds.size >= 10) sortedSpeeds[(sortedSpeeds.size * 0.1).toInt()] else null
        val p90Speed = if (sortedSpeeds.size >= 10) sortedSpeeds[(sortedSpeeds.size * 0.9).toInt()] else null
        val speedStdDev = if (speeds.size >= 2) {
            val mean = speeds.map { it.toDouble() }.average()
            val variance = speeds.map { (it - mean) * (it - mean) }.average()
            kotlin.math.sqrt(variance)
        } else null

        // Stops detection (speed < 0.5 km/h for consecutive points)
        var stopCount = 0
        var totalStopDurationMs = 0L
        var inStop = false
        var stopStartTime = 0L
        for (pt in points) {
            if (pt.speedKmh < 0.5f) {
                if (!inStop) {
                    inStop = true
                    stopStartTime = pt.timestamp
                    stopCount++
                }
            } else {
                if (inStop) {
                    totalStopDurationMs += pt.timestamp - stopStartTime
                    inStop = false
                }
            }
        }
        if (inStop && points.isNotEmpty()) {
            totalStopDurationMs += points.last().timestamp - stopStartTime
        }
        val stopDurationMin = TimeUnit.MILLISECONDS.toMinutes(totalStopDurationMs)
        val movingTimeMs = durationMs - totalStopDurationMs
        val movingTimeMin = TimeUnit.MILLISECONDS.toMinutes(movingTimeMs.coerceAtLeast(0))

        // Activity segments
        val activitySegments = points.groupBy { it.activityType }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        // Battery
        val batteryDrain = if (track.batteryStart != null && track.batteryEnd != null)
            track.batteryStart - track.batteryEnd else null

        // Pace for walking/running
        val paceMinPerKm = if (track.distanceMeters > 0 && movingTimeMin > 0) {
            movingTimeMin.toDouble() / (track.distanceMeters / 1000.0)
        } else null

        // GPS accuracy stats
        val accuracies = points.mapNotNull { it.accuracy }
        val avgAccuracy = accuracies.takeIf { it.isNotEmpty() }?.average()

        // GPS point count
        val totalPoints = points.size

        return buildString {
            appendLine("You are a fitness and journey analyst. Analyze this GPS-tracked activity and return a JSON object with these keys:")
            appendLine("- activity (string): primary activity — WALKING, RUNNING, CYCLING, DRIVING, FLYING, or STATIONARY")
            appendLine("- confidence (0.0-1.0): classification confidence based on speed and movement patterns")
            appendLine("- summary (string): 3-5 sentences summarizing the journey with specific numbers, performance assessment, and notable patterns from the data")
            appendLine("- suggestions (array of strings): 3-5 actionable tips referencing the actual metrics below, covering performance, safety, and improvement areas")
            appendLine("- healthInsights (string or null): heart rate zone analysis if HR data present (resting/fat-burn/cardio/peak zones), otherwise null")
            appendLine()
            appendLine("Track Data:")
            appendLine("- Started: $dateStr ($timeOfDay)")
            appendLine("- Activity: ${track.activityType}")
            appendLine("- Distance: ${"%.2f".format(track.distanceMeters / 1000)}km (${"%.0f".format(track.distanceMeters)}m)")
            appendLine("- Total Duration: ${durationMin}m ${durationSec}s")
            appendLine("- Moving Time: ${movingTimeMin}m (stopped: ${stopDurationMin}m across $stopCount stops)")
            appendLine("- GPS Points: $totalPoints recorded")
            appendLine()
            appendLine("Speed Analysis:")
            appendLine("- Avg Speed: ${"%.1f".format(track.avgSpeedKmh)} km/h")
            appendLine("- Max Speed: ${"%.1f".format(track.maxSpeedKmh)} km/h")
            appendLine("- Median Speed: ${"%.1f".format(medianSpeed)} km/h")
            if (p10Speed != null && p90Speed != null) {
                appendLine("- Speed Range (10th-90th percentile): ${"%.1f".format(p10Speed)}-${"%.1f".format(p90Speed)} km/h")
            }
            if (speedStdDev != null) {
                appendLine("- Speed Variability (std dev): ${"%.1f".format(speedStdDev)} km/h")
            }
            if (paceMinPerKm != null && track.avgSpeedKmh < 20) {
                appendLine("- Pace: ${"%.1f".format(paceMinPerKm)} min/km")
            }
            appendLine()
            appendLine("Energy & Elevation:")
            appendLine("- Calories: ${"%.0f".format(track.caloriesBurned)} kcal")
            if (minAlt != null && maxAlt != null) {
                appendLine("- Elevation: ${"%.0f".format(minAlt)}-${"%.0f".format(maxAlt)}m (range: ${"%.0f".format(maxAlt - minAlt)}m)")
                appendLine("- Elevation Gain: ${"%.0f".format(elevationGain)}m, Loss: ${"%.0f".format(elevationLoss)}m")
            }

            if (avgHr != null) {
                appendLine()
                appendLine("Heart Rate:")
                appendLine("- Avg: $avgHr bpm, Max: $maxHr bpm, Min: $minHr bpm")
                appendLine("- Range: ${(maxHr ?: 0) - (minHr ?: 0)} bpm spread")
            }
            if (avgCadence != null) {
                appendLine("- Cadence: avg $avgCadence spm, max $maxCadence spm")
            }
            if (track.startPlaceName != null || track.endPlaceName != null) {
                appendLine()
                appendLine("Route: ${track.startPlaceName ?: "?"} → ${track.endPlaceName ?: "?"}")
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
            if (avgAccuracy != null) {
                appendLine("- GPS Accuracy: ${"%.1f".format(avgAccuracy)}m avg")
            }

            appendLine()
            appendLine("Use the actual numbers in your summary and suggestions. Identify patterns like inconsistent pacing, long stops, unusual speed variations. If the activity type seems wrong for the speed, flag it. Return valid JSON only.")
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

    private fun computeElevationLoss(altitudes: List<Double>): Double {
        if (altitudes.size < 2) return 0.0
        var loss = 0.0
        for (i in 1 until altitudes.size) {
            val diff = altitudes[i] - altitudes[i - 1]
            if (diff < 0) loss -= diff
        }
        return loss
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

package com.trackjourney.data.ai.provider

import android.util.Log
import com.trackjourney.BuildConfig
import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.LocalModelManager
import com.trackjourney.data.ai.models.ModelCatalog
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

    private fun ensureModelLoaded(runtime: LocalModelRuntime, runtimeType: String, localPath: String?, downloadUrl: String?) {
        if (!runtime.isAvailable()) {
            if (localPath == null) {
                throw IllegalStateException("Model needs loading but no local path is available for runtime: $runtimeType")
            }
            Log.i(TAG, "Loading model from: $localPath (runtime: $runtimeType)")
            when (runtime) {
                is MediaPipeLlmRuntimeAdapter -> runtime.loadModel(localPath, downloadUrl)
                is LiteRtRuntimeAdapter -> runtime.loadModel(localPath, downloadUrl)
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

        val downloadUrl = activeModel.downloadUrl
            ?: ModelCatalog.findById(activeModel.modelId)?.downloadUrl
        ensureModelLoaded(runtime, activeModel.runtimeType, activeModel.localPath, downloadUrl)

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

        val downloadUrl = activeModel.downloadUrl
            ?: ModelCatalog.findById(activeModel.modelId)?.downloadUrl
        ensureModelLoaded(runtime, activeModel.runtimeType, activeModel.localPath, downloadUrl)

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

        // Heart rate
        val heartRates = points.mapNotNull { it.heartRate } +
                healthData.mapNotNull { it.heartRate }
        val avgHr = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val maxHr = heartRates.maxOrNull()
        val minHr = heartRates.minOrNull()

        // Speed
        val speeds = points.map { it.speedKmh }.filter { it > 0 }
        val medianSpeed = speeds.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 0f }
        val speedP90 = speeds.sorted().let { if (it.size >= 5) it[(it.size * 0.9).toInt()] else null }

        // Stops
        var stopCount = 0
        var totalStopMs = 0L
        var inStop = false
        var stopStartIdx = 0
        for ((idx, pt) in points.withIndex()) {
            if (pt.speedKmh < 0.5f) {
                if (!inStop) { inStop = true; stopCount++; stopStartIdx = idx }
            } else {
                if (inStop && idx > 0 && stopStartIdx > 0) {
                    totalStopMs += points[idx].timestamp - points[stopStartIdx].timestamp
                }
                inStop = false
            }
        }
        val stopMin = TimeUnit.MILLISECONDS.toMinutes(totalStopMs)

        // Elevation
        val altitudes = points.mapNotNull { it.altitude }
        val elevGain = computeElevationGain(altitudes)
        val elevLoss = computeElevationLoss(altitudes)
        val minAlt = altitudes.minOrNull()
        val maxAlt = altitudes.maxOrNull()

        // Pace
        val paceMinPerKm = if (track.distanceMeters > 0 && durationMin > 0) {
            durationMin.toDouble() / (track.distanceMeters / 1000.0)
        } else null

        // Build a compact structured prompt that fits in small context windows (~200 tokens)
        return buildString {
            appendLine("Analyze GPS trip. JSON only: {\"activity\":\"WALKING|RUNNING|CYCLING|DRIVING|FLYING|STATIONARY\",\"confidence\":0.0-1.0,\"summary\":\"...\",\"suggestions\":[\"...\"],\"healthInsights\":\"...or null\"}")
            appendLine("---")
            appendLine("type:${track.activityType} dist:${"%.1f".format(track.distanceMeters/1000)}km dur:${durationMin}min pts:${points.size}")
            append("spd avg:${"%.1f".format(track.avgSpeedKmh)} max:${"%.1f".format(track.maxSpeedKmh)} med:${"%.1f".format(medianSpeed)}")
            if (speedP90 != null) append(" p90:${"%.1f".format(speedP90)}")
            appendLine(" km/h")
            if (paceMinPerKm != null && track.avgSpeedKmh < 20) appendLine("pace:${"%.1f".format(paceMinPerKm)}min/km")
            append("stops:$stopCount")
            if (stopMin > 0) append(" stopTime:${stopMin}min")
            appendLine(" cal:${"%.0f".format(track.caloriesBurned)}")
            if (elevGain > 0 || elevLoss > 0) {
                append("elev +${"%.0f".format(elevGain)}m -${"%.0f".format(elevLoss)}m")
                if (minAlt != null && maxAlt != null) append(" range:${"%.0f".format(minAlt)}-${"%.0f".format(maxAlt)}m")
                appendLine()
            }
            if (avgHr != null) {
                appendLine("hr avg:$avgHr max:$maxHr min:$minHr bpm")
            }
            if (track.startPlaceName != null || track.endPlaceName != null) {
                appendLine("route:${track.startPlaceName ?: "?"}→${track.endPlaceName ?: "?"}")
            }
        }
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        return buildString {
            appendLine("Analyze ${snapshots.size} trips. JSON only: {\"totalDistance\":m,\"totalCalories\":n,\"dominantActivity\":\"...\",\"weekSummary\":\"...\",\"improvements\":[\"...\"]}")
            appendLine("---")

            var totalDist = 0.0
            var totalCal = 0.0
            var totalDurMin = 0L
            snapshots.forEachIndexed { i, twp ->
                val t = twp.track
                val dur = TimeUnit.MILLISECONDS.toMinutes((t.endTime ?: System.currentTimeMillis()) - t.startTime)
                totalDist += t.distanceMeters; totalCal += t.caloriesBurned; totalDurMin += dur
                val hrPart = t.avgHeartRate?.let { " hr:${it}" } ?: ""
                appendLine("${i+1}.${t.activityType} ${"%.1f".format(t.distanceMeters/1000)}km ${dur}m ${"%.1f".format(t.avgSpeedKmh)}km/h ${"%.0f".format(t.caloriesBurned)}cal$hrPart")
            }
            appendLine("Tot:${"%.1f".format(totalDist/1000)}km ${"%.0f".format(totalCal)}cal ${totalDurMin}min")
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

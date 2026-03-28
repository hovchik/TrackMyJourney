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
        // 1. Try parsing as-is
        try {
            val jsonObj = JSONObject(json)
            val missingKeys = requiredKeys.filter { !jsonObj.has(it) }
            if (missingKeys.isNotEmpty()) {
                Log.w(TAG, "AI response missing keys: $missingKeys")
            }
            return json
        } catch (_: Exception) { /* fall through to repair */ }

        // 2. Try repairing truncated JSON (local models often hit output token limits)
        val repaired = repairTruncatedJson(json)
        if (repaired != null) {
            try {
                val jsonObj = JSONObject(repaired)
                Log.i(TAG, "Repaired truncated JSON successfully")
                val missingKeys = requiredKeys.filter { !jsonObj.has(it) }
                if (missingKeys.isNotEmpty()) {
                    Log.w(TAG, "Repaired JSON missing keys: $missingKeys")
                }
                return repaired
            } catch (_: Exception) { /* fall through to fallback */ }
        }

        // 3. Last resort: build fallback from raw text
        Log.e(TAG, "AI response is not valid JSON: cannot parse or repair")
        if (BuildConfig.DEBUG) Log.d(TAG, "Raw response: $json")
        return buildFallbackJson(json)
    }

    /**
     * Attempts to repair JSON that was truncated mid-output by the local model.
     * Closes any open strings, arrays, and objects to make it parseable.
     * Preserves all the valid data that was generated before truncation.
     */
    private fun repairTruncatedJson(json: String): String? {
        // Only attempt repair if it starts like JSON but doesn't end properly
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return null
        if (trimmed.endsWith("}")) return null // already closed, problem is elsewhere

        val sb = StringBuilder(trimmed)

        // Track nesting state
        var inString = false
        var escaped = false
        var braceDepth = 0
        var bracketDepth = 0

        for (ch in trimmed) {
            if (escaped) { escaped = false; continue }
            if (ch == '\\' && inString) { escaped = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (inString) continue
            when (ch) {
                '{' -> braceDepth++
                '}' -> braceDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
            }
        }

        // Close open string (truncated mid-value)
        if (inString) {
            sb.append("\"")
        }

        // Close open arrays
        repeat(bracketDepth) { sb.append("]") }

        // Close open objects
        repeat(braceDepth) { sb.append("}") }

        val result = sb.toString()
        // Verify it actually parses now
        return try {
            JSONObject(result)
            result
        } catch (_: Exception) {
            // Try removing a trailing partial key-value (e.g. truncated after comma + key)
            // Find the last complete key-value pair
            val lastGoodEnd = findLastCompleteValue(result)
            if (lastGoodEnd != null) lastGoodEnd else null
        }
    }

    /**
     * Attempts to find a valid JSON by trimming back to the last complete value.
     */
    private fun findLastCompleteValue(json: String): String? {
        // Try progressively removing trailing content until we get valid JSON
        var candidate = json
        for (i in 0 until 5) {
            // Remove trailing partial content after last comma or colon
            val lastComma = candidate.lastIndexOf(',')
            val lastColon = candidate.lastIndexOf(':')
            val cutPoint = maxOf(lastComma, lastColon)
            if (cutPoint <= 0) return null
            candidate = candidate.substring(0, cutPoint)
            // Re-close brackets/braces
            var braces = 0; var brackets = 0; var inStr = false; var esc = false
            for (ch in candidate) {
                if (esc) { esc = false; continue }
                if (ch == '\\' && inStr) { esc = true; continue }
                if (ch == '"') { inStr = !inStr; continue }
                if (inStr) continue
                when (ch) { '{' -> braces++; '}' -> braces--; '[' -> brackets++; ']' -> brackets-- }
            }
            val closed = candidate + "]".repeat(brackets) + "}".repeat(braces)
            try {
                JSONObject(closed)
                Log.i(TAG, "Recovered truncated JSON by trimming $i trailing fragments")
                return closed
            } catch (_: Exception) { /* try trimming more */ }
        }
        return null
    }

    /**
     * Constructs a valid JSON response from plain-text AI output that failed JSON parsing.
     * Attempts to extract activity type and uses the raw text as the summary.
     */
    private fun buildFallbackJson(rawText: String): String {
        val upper = rawText.uppercase()
        val activity = listOf("WALKING", "RUNNING", "CYCLING", "DRIVING", "FLYING", "STATIONARY")
            .firstOrNull { it in upper } ?: "UNKNOWN"

        // Clean up the raw text for use as summary (first 300 chars, single line)
        val summary = rawText
            .replace("\n", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .take(300)

        val fallback = JSONObject().apply {
            put("activity", activity)
            put("confidence", 0.3)
            put("summary", summary)
            put("suggestions", org.json.JSONArray())
            put("healthInsights", JSONObject.NULL)
        }
        Log.w(TAG, "Built fallback JSON from plain-text response, detected activity: $activity")
        return fallback.toString()
    }

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints, lifetimeContext: LifetimeContext?): String {
        val activeModel = modelManager.getActiveModelSync()
            ?: throw IllegalStateException("No active local model configured")
        val runtime = resolveRuntime(activeModel.runtimeType)
            ?: throw IllegalStateException("No runtime available for ${activeModel.runtimeType}")

        val downloadUrl = activeModel.downloadUrl
            ?: ModelCatalog.findById(activeModel.modelId)?.downloadUrl
        ensureModelLoaded(runtime, activeModel.runtimeType, activeModel.localPath, downloadUrl)

        val prompt = buildDailyPrompt(snapshot, lifetimeContext)
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

    private fun buildDailyPrompt(snapshot: TrackWithPoints, lifetimeContext: LifetimeContext? = null): String {
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

        // Speed consistency
        val speedStdDev = if (speeds.size >= 2) {
            val mean = speeds.average()
            kotlin.math.sqrt(speeds.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0f

        // Time of day
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = track.startTime
        val startHour = cal.get(java.util.Calendar.HOUR_OF_DAY)

        // Cadence
        val cadences = points.mapNotNull { it.cadence }
        val avgCadence = cadences.takeIf { it.isNotEmpty() }?.average()?.toInt()

        // GPS accuracy
        val accuracies = points.mapNotNull { it.accuracy }
        val avgAccuracy = accuracies.takeIf { it.isNotEmpty() }?.average()
        val satellites = points.mapNotNull { it.satellitesUsed }
        val avgSatellites = satellites.takeIf { it.isNotEmpty() }?.average()?.toInt()

        // Bearing variability
        val bearings = points.mapNotNull { it.bearing }
        val bearingStdDev = if (bearings.size >= 2) {
            val mean = bearings.average()
            kotlin.math.sqrt(bearings.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else null

        // Wearable
        val wearableDevice = snapshot.healthData.firstOrNull { it.deviceName != null }

        // Build a compact structured prompt that fits in small context windows
        // IMPORTANT: instruct model to output ONLY a JSON object, no prose
        return buildString {
            appendLine("RESPOND WITH ONLY A JSON OBJECT. No other text before or after the JSON.")
            appendLine("Return: {\"activity\":\"STATIONARY|WALKING|RUNNING|CYCLING|DRIVING|FLYING\",\"confidence\":0.0-1.0,\"summary\":\"2-3 short sentences\",\"suggestions\":[\"1-2 tips\"],\"healthInsights\":\"1 sentence or null\",\"lifetimeInsights\":\"1 sentence or null\"}")
            appendLine("Speed guide: STATIONARY<0.5 WALKING<7 RUNNING<15 CYCLING<40 DRIVING<200 FLYING>200 km/h")
            appendLine("---")
            // Show user's manual override if set
            if (track.customActivityType != null) {
                appendLine("userType:${track.customActivityType} autoType:${track.activityType} dist:${"%.1f".format(track.distanceMeters/1000)}km dur:${durationMin}min pts:${points.size} time:${startHour}h")
            } else {
                appendLine("type:${track.activityType} dist:${"%.1f".format(track.distanceMeters/1000)}km dur:${durationMin}min pts:${points.size} time:${startHour}h")
            }
            append("spd avg:${"%.1f".format(track.avgSpeedKmh)} max:${"%.1f".format(track.maxSpeedKmh)} med:${"%.1f".format(medianSpeed)}")
            if (speedP90 != null) append(" p90:${"%.1f".format(speedP90)}")
            append(" sd:${"%.1f".format(speedStdDev)}")
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
            if (avgCadence != null) appendLine("cadence:$avgCadence spm")
            if (track.startPlaceName != null || track.endPlaceName != null) {
                appendLine("route:${track.startPlaceName ?: "?"}→${track.endPlaceName ?: "?"}")
            }
            if (track.rideCost != null) {
                appendLine("cost:${"%.2f".format(track.rideCost)}")
            }
            if (avgAccuracy != null) append("gps:${"%.0f".format(avgAccuracy)}m")
            if (avgSatellites != null) append(" sat:$avgSatellites")
            if (avgAccuracy != null || avgSatellites != null) appendLine()
            if (bearingStdDev != null) appendLine("turns:${"%.0f".format(bearingStdDev)}°")
            if (track.batteryStart != null && track.batteryEnd != null) {
                appendLine("bat:${track.batteryStart}→${track.batteryEnd}%")
            }
            if (wearableDevice?.deviceName != null) {
                appendLine("wear:${wearableDevice.deviceName}")
            }
            // Compact lifetime context
            if (lifetimeContext != null && lifetimeContext.totalTracks >= 2) {
                appendLine("history: ${lifetimeContext.totalTracks}trips avg:${"%.1f".format(lifetimeContext.avgDistanceKm)}km ${"%.1f".format(lifetimeContext.avgSpeedKmh)}km/h best:${"%.1f".format(lifetimeContext.bestDistanceKm)}km ${"%.1f".format(lifetimeContext.bestSpeedKmh)}km/h")
            }
            appendLine("---")
            appendLine("IMPORTANT: Output ONLY valid JSON. No explanations, no markdown code blocks, no ``` wrapping, no text before or after the JSON object.")
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
        var cleaned = output.trim()
        // Strip markdown code blocks: ```json ... ``` or ``` ... ```
        if (cleaned.startsWith("```")) {
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1)
            }
            val lastBackticks = cleaned.lastIndexOf("```")
            if (lastBackticks != -1) {
                cleaned = cleaned.substring(0, lastBackticks)
            }
            cleaned = cleaned.trim()
        }
        // Extract JSON object
        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        return if (jsonStart >= 0 && jsonEnd > jsonStart) {
            cleaned.substring(jsonStart, jsonEnd + 1)
        } else {
            cleaned
        }
    }
}

package com.trackmyjourney.data.ai.provider

import android.util.Log
import com.trackmyjourney.BuildConfig
import com.trackmyjourney.data.ai.models.AiExecutionMode
import com.trackmyjourney.data.ai.models.AiPreferences
import com.trackmyjourney.data.ai.models.CloudProviderType
import com.trackmyjourney.data.local.SettingsDataStore
import com.trackmyjourney.data.model.CloudAiProvider
import com.trackmyjourney.data.model.TrackWithPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud-based AI provider that sends summarized track data to cloud AI APIs
 * for analysis. Supports OpenAI, DeepSeek, Gemini (all OpenAI-compatible format)
 * and Claude (Anthropic Messages API).
 */
@Singleton
class CloudProvider @Inject constructor(
    private val aiPreferences: AiPreferences,
    private val settingsDataStore: SettingsDataStore
) : AiAnalysisProvider {

    companion object {
        private const val TAG = "CloudProvider"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000

        // App-bundled DeepSeek key — users don't need to bring their own.
        // NOTE: anything baked into an APK can be extracted via reverse
        // engineering, so usage from leaked copies of the app will bill
        // this account.
        private const val BUILTIN_DEEPSEEK_KEY = "sk-22b7971bba6c45ef86e2309721d85260"
    }

    override val executionMode: AiExecutionMode = AiExecutionMode.CLOUD
    override val displayName: String = "Cloud AI"

    private var apiKey: String? = null
    private var providerType: CloudProviderType = CloudProviderType.CLAUDE
    private var customEndpoint: String = ""
    private var customModel: String = ""

    /**
     * Loads cloud AI config from SettingsDataStore (the source of truth,
     * written by the Settings screen's Cloud AI wizard).
     * Always reloads to pick up any changes the user made.
     *
     * For DeepSeek the app ships a built-in key, so the user never needs
     * to enter one — the hardcoded constant is used directly.
     * For other providers, the user must supply a key via Settings.
     */
    suspend fun ensureConfigLoaded() {
        val settings = settingsDataStore.settings.first()

        providerType = mapSettingsProvider(settings.cloudAiProvider)
        customEndpoint = settings.cloudAiEndpoint
        customModel = settings.cloudAiModel

        // For DeepSeek we ship a built-in key.  Other providers fall back to
        // BuildConfig (populated from local.properties at build time) when the
        // user hasn't entered one yet.
        val builtInKey = when (providerType) {
            CloudProviderType.DEEPSEEK  -> BUILTIN_DEEPSEEK_KEY
            CloudProviderType.OPENAI    -> BuildConfig.OPENAI_API_KEY
            CloudProviderType.CLAUDE    -> BuildConfig.ANTHROPIC_API_KEY
            CloudProviderType.GEMINI    -> BuildConfig.GEMINI_API_KEY
        }.takeIf { it.isNotBlank() }

        apiKey = if (providerType == CloudProviderType.DEEPSEEK) {
            // DeepSeek always uses the bundled key — ignore any stored value.
            builtInKey
        } else {
            settings.cloudAiApiKey.takeIf { it.isNotBlank() }
                ?: aiPreferences.getCloudApiKey()
                ?: builtInKey
        }

        Log.d(TAG, "Config loaded: provider=${providerType.label}, keySet=${!apiKey.isNullOrBlank()}")
    }

    /**
     * Maps the Settings screen's CloudAiProvider enum to our CloudProviderType.
     */
    private fun mapSettingsProvider(provider: CloudAiProvider): CloudProviderType = when (provider) {
        CloudAiProvider.OPENAI -> CloudProviderType.OPENAI
        CloudAiProvider.ANTHROPIC -> CloudProviderType.CLAUDE
        CloudAiProvider.GEMINI -> CloudProviderType.GEMINI
        CloudAiProvider.DEEPSEEK -> CloudProviderType.DEEPSEEK
        CloudAiProvider.CUSTOM -> CloudProviderType.OPENAI // custom endpoint uses OpenAI format
    }

    fun setApiKey(key: String) {
        apiKey = key
    }

    fun setProviderType(type: CloudProviderType) {
        providerType = type
    }

    fun getProviderType(): CloudProviderType = providerType

    override fun isConfigured(): Boolean = !apiKey.isNullOrBlank()

    override fun isAvailable(): Boolean = isConfigured()

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints, lifetimeContext: LifetimeContext?): String {
        ensureConfigLoaded()
        if (!isConfigured()) {
            throw IllegalStateException("Cloud AI not configured. Set API key in Settings → AI Engine → Cloud AI.")
        }
        val prompt = buildDailyPrompt(snapshot, lifetimeContext)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Prompt [daily] (${providerType.label}):\n$prompt")
        return callApi(prompt)
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        ensureConfigLoaded()
        if (!isConfigured()) {
            throw IllegalStateException("Cloud AI not configured. Set API key in Settings → AI Engine → Cloud AI.")
        }
        val prompt = buildWeeklyPrompt(snapshots)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Prompt [weekly] (${providerType.label}):\n$prompt")
        return callApi(prompt)
    }

    // ── API call logic ──────────────────────────────────────────────────────

    private fun getBaseUrl(): String {
        return when (providerType) {
            CloudProviderType.OPENAI -> "https://api.openai.com/v1/chat/completions"
            CloudProviderType.DEEPSEEK -> "https://api.deepseek.com/v1/chat/completions"
            CloudProviderType.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
            CloudProviderType.CLAUDE -> "https://api.anthropic.com/v1/messages"
        }
    }

    private fun getModel(): String {
        return when (providerType) {
            CloudProviderType.OPENAI -> "gpt-4o-mini"
            CloudProviderType.DEEPSEEK -> "deepseek-chat"
            CloudProviderType.GEMINI -> "gemini-2.5-flash"
            CloudProviderType.CLAUDE -> "claude-sonnet-4-20250514"
        }
    }

    private suspend fun callApi(prompt: String): String = withContext(Dispatchers.IO) {
        val key = apiKey ?: throw IllegalStateException("API key not set.")
        if (providerType == CloudProviderType.CLAUDE) {
            callAnthropicApi(prompt, key)
        } else {
            callOpenAiCompatibleApi(prompt, key)
        }
    }

    /**
     * Calls an OpenAI-compatible /v1/chat/completions endpoint.
     * Works for OpenAI, DeepSeek, and Gemini.
     */
    private fun callOpenAiCompatibleApi(prompt: String, key: String): String {
        val requestBody = JSONObject().apply {
            put("model", getModel())
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You analyze GPS-tracked activity data and respond with a single JSON object that exactly matches the schema in the user prompt.  Use ONLY numbers from the user prompt — never invent metrics.  No markdown, no code fences, no prose before or after the JSON.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 2048)
        }

        val response = executeHttpRequest(
            url = getBaseUrl(),
            body = requestBody.toString(),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $key"
            )
        )

        return parseOpenAiResponse(response)
    }

    /**
     * Calls the Anthropic Messages API (different format from OpenAI).
     */
    private fun callAnthropicApi(prompt: String, key: String): String {
        val requestBody = JSONObject().apply {
            put("model", getModel())
            put("max_tokens", 2048)
            put("temperature", 0.1)
            put("system", "You analyze GPS-tracked activity data and respond with a single JSON object that exactly matches the schema in the user prompt.  Use ONLY numbers from the user prompt — never invent metrics.  No markdown, no code fences, no prose before or after the JSON.")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val response = executeHttpRequest(
            url = getBaseUrl(),
            body = requestBody.toString(),
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01"
            )
        )

        return parseAnthropicResponse(response)
    }

    private fun executeHttpRequest(
        url: String,
        body: String,
        headers: Map<String, String>
    ): String {
        Log.d(TAG, "API request: POST $url (${providerType.label})")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }

            if (responseCode !in 200..299) {
                Log.e(TAG, "API error ($responseCode): $responseText")
                val errorMsg = try {
                    val errJson = JSONObject(responseText)
                    errJson.optJSONObject("error")?.optString("message")
                        ?: errJson.optString("message", responseText)
                } catch (_: Exception) {
                    responseText.take(300)
                }
                throw RuntimeException("${providerType.label} API error ($responseCode): $errorMsg")
            }

            return responseText
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parses the OpenAI-compatible response format:
     * { "choices": [{ "message": { "content": "..." } }] }
     */
    private fun parseOpenAiResponse(response: String): String {
        return try {
            val json = JSONObject(response)
            val choices = json.getJSONArray("choices")
            val content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            extractJsonFromContent(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse OpenAI response: ${response.take(500)}", e)
            throw RuntimeException("Failed to parse ${providerType.label} response: ${e.message}")
        }
    }

    /**
     * Parses the Anthropic Messages API response format:
     * { "content": [{ "type": "text", "text": "..." }] }
     */
    private fun parseAnthropicResponse(response: String): String {
        return try {
            val json = JSONObject(response)
            val content = json.getJSONArray("content")
            val text = content.getJSONObject(0).getString("text").trim()
            extractJsonFromContent(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Anthropic response: ${response.take(500)}", e)
            throw RuntimeException("Failed to parse Claude response: ${e.message}")
        }
    }

    /**
     * Extracts JSON from the AI response content. The model might wrap JSON
     * in markdown code blocks or include extra text.
     */
    private fun extractJsonFromContent(content: String): String {
        // Strip markdown code blocks if present: ```json ... ``` or ``` ... ```
        var cleaned = content.trim()
        if (cleaned.startsWith("```")) {
            // Remove opening ``` line (possibly with "json" label)
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1)
            }
            // Remove closing ```
            val lastBackticks = cleaned.lastIndexOf("```")
            if (lastBackticks != -1) {
                cleaned = cleaned.substring(0, lastBackticks)
            }
            cleaned = cleaned.trim()
        }

        // Find the JSON object
        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            val candidate = cleaned.substring(jsonStart, jsonEnd + 1)
            try {
                JSONObject(candidate)
                return candidate
            } catch (_: Exception) {
                // Fall through
            }
        }

        return cleaned
    }

    // ── Prompt builders ─────────────────────────────────────────────────────

    private fun buildDailyPrompt(snapshot: TrackWithPoints, lifetimeContext: LifetimeContext? = null): String {
        val track = snapshot.track
        val points = snapshot.points

        val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
        val durationMin = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val durationSec = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60

        val altitudes = points.mapNotNull { it.altitude }
        val elevationGain = computeElevationGain(altitudes)
        val speeds = points.map { it.speedKmh }.filter { it > 0 }
        val medianSpeed = speeds.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 0f }
        val avgCadence = points.mapNotNull { it.cadence }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val paceMinPerKm = if (track.distanceMeters > 0 && durationMin > 0) {
            (durationMin + durationSec / 60.0) / (track.distanceMeters / 1000.0)
        } else null

        val elevationLoss = computeElevationLoss(altitudes)
        val minAlt = altitudes.minOrNull()
        val maxAlt = altitudes.maxOrNull()

        // Stops analysis
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

        // Activity segments
        val activitySegments = points.groupBy { it.activityType }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        // Speed distribution
        val speedBuckets = speeds.groupBy {
            when {
                it < 1 -> "0-1"
                it < 5 -> "1-5"
                it < 10 -> "5-10"
                it < 20 -> "10-20"
                it < 40 -> "20-40"
                it < 80 -> "40-80"
                it < 120 -> "80-120"
                else -> "120+"
            }
        }.mapValues { it.value.size }

        // Speed consistency (std deviation)
        val speedStdDev = if (speeds.size >= 2) {
            val mean = speeds.average()
            kotlin.math.sqrt(speeds.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0f

        // GPS accuracy
        val accuracies = points.mapNotNull { it.accuracy }
        val avgAccuracy = accuracies.takeIf { it.isNotEmpty() }?.average()

        // Time of day
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = track.startTime
        val startHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when (startHour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }

        // Cadence details
        val cadences = points.mapNotNull { it.cadence }
        val minCadence = cadences.minOrNull()
        val maxCadence = cadences.maxOrNull()

        // Speed profile — sample 5 segments across the track
        val speedProfile = if (points.size >= 10) {
            val segSize = points.size / 5
            (0 until 5).map { i ->
                val seg = points.subList(i * segSize, minOf((i + 1) * segSize, points.size))
                val segAvg = seg.map { it.speedKmh }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
                "%.1f".format(segAvg)
            }.joinToString(" → ")
        } else null

        // Bearing analysis
        val bearings = points.mapNotNull { it.bearing }
        val avgBearing = bearings.takeIf { it.isNotEmpty() }?.average()
        val bearingStdDev = if (bearings.size >= 2) {
            val mean = bearings.average()
            kotlin.math.sqrt(bearings.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else null

        // Satellite data
        val satellites = points.mapNotNull { it.satellitesUsed }
        val avgSatellites = satellites.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val inaccuratePoints = points.count { !it.isAccurate }

        return buildString {
            appendLine("Analyze the GPS-tracked activity below.  Return ONE JSON object that matches this schema exactly — no extra keys, no missing keys, no markdown, no prose before or after:")
            appendLine("{")
            appendLine("  \"activity\": \"STATIONARY|WALKING|RUNNING|CYCLING|DRIVING|FLYING\",")
            appendLine("  \"confidence\": 0.0,            // number in [0.0, 1.0]")
            appendLine("  \"summary\": \"\",              // 3–5 sentences, ~60–110 words, cite numbers from TRACK DATA")
            appendLine("  \"suggestions\": [\"\"],        // 3–5 short, actionable tips referencing actual metrics")
            appendLine("  \"lifetimeInsights\": null     // string (2–3 sentences) ONLY if LIFETIME STATS is present, else literal null — never the string \"null\"")
            appendLine("}")
            appendLine()
            appendLine("Rules:")
            appendLine("- Use ONLY the numbers in TRACK DATA / LIFETIME STATS.  Do not invent metrics (weather, heart rate, mood, weight, etc.).")
            appendLine("- Activity speed thresholds (km/h): STATIONARY <0.5, WALKING 0.5–7, RUNNING 7–15, CYCLING 15–35, DRIVING 35–200, FLYING ≥200.")
            appendLine("- If a `User-set activity` is provided, return that exact value as `activity` and do not contradict the user's choice in the summary.")
            appendLine("- Round numbers in prose to at most 1 decimal place.  Keep `confidence` between 0 and 1.")
            appendLine("- The output MUST be parseable by JSON.parse — quotes around all keys, no trailing commas, no comments.")
            appendLine()
            appendLine("=== TRACK DATA ===")
            // Show user's manual override if set
            if (track.customActivityType != null) {
                appendLine("User-set activity: ${track.customActivityType} (treat this as the correct activity type)")
                appendLine("Auto-detected activity: ${track.activityType}")
            } else {
                appendLine("Activity: ${track.activityType}")
            }
            appendLine("Time: $timeOfDay start (${startHour}:00), Duration: ${durationMin}m ${durationSec}s")
            appendLine("Distance: ${"%.2f".format(track.distanceMeters / 1000)}km")
            appendLine("Speed: avg ${"%.1f".format(track.avgSpeedKmh)}, max ${"%.1f".format(track.maxSpeedKmh)}, median ${"%.1f".format(medianSpeed)}, stdDev ${"%.1f".format(speedStdDev)} km/h")
            if (speedProfile != null) appendLine("Speed profile (start→end): $speedProfile km/h")
            appendLine("Speed distribution: ${speedBuckets.entries.sortedBy { it.key }.joinToString { "${it.key}km/h:${it.value}pts" }}")
            appendLine("Calories: ${"%.0f".format(track.caloriesBurned)} kcal")
            appendLine("GPS points: ${points.size}")
            if (avgAccuracy != null) appendLine("GPS accuracy: avg ${"%.1f".format(avgAccuracy)}m")
            if (paceMinPerKm != null && track.avgSpeedKmh < 20) appendLine("Pace: ${"%.1f".format(paceMinPerKm)} min/km")
            if (elevationGain > 0 || elevationLoss > 0) {
                append("Elevation: gain ${"%.0f".format(elevationGain)}m, loss ${"%.0f".format(elevationLoss)}m")
                if (minAlt != null && maxAlt != null) append(", range ${"%.0f".format(minAlt)}-${"%.0f".format(maxAlt)}m")
                appendLine()
            }
            if (avgCadence != null) appendLine("Cadence: avg $avgCadence, min $minCadence, max $maxCadence spm")
            if (stopCount > 0) {
                append("Stops: $stopCount")
                if (stopMin > 0) append(", total stop time: ${stopMin}min")
                appendLine()
            }
            if (track.startPlaceName != null || track.endPlaceName != null) {
                appendLine("Route: ${track.startPlaceName ?: "?"} → ${track.endPlaceName ?: "?"}")
            }
            if (activitySegments.size > 1) {
                appendLine("Segments: ${activitySegments.joinToString { "${it.key}(${if (points.isNotEmpty()) (it.value * 100) / points.size else 0}%)" }}")
            }
            if (track.rideCost != null) {
                appendLine("Ride Cost: ${"%.2f".format(track.rideCost)}")
            }
            // Bearing / direction consistency
            if (avgBearing != null && bearingStdDev != null) {
                appendLine("Bearing: avg ${"%.0f".format(avgBearing)}°, variability ${"%.0f".format(bearingStdDev)}° (low = straight route, high = many turns)")
            }
            // GPS quality
            if (avgSatellites != null) appendLine("GPS satellites: avg $avgSatellites")
            if (inaccuratePoints > 0) appendLine("Inaccurate GPS points: $inaccuratePoints of ${points.size}")
            // Battery
            if (track.batteryStart != null || track.batteryEnd != null) {
                appendLine("Phone battery: ${track.batteryStart ?: "?"}% → ${track.batteryEnd ?: "?"}%")
            }
            // Lifetime context for comparative analysis
            if (lifetimeContext != null && lifetimeContext.totalTracks >= 2) {
                appendLine()
                appendLine("=== LIFETIME STATS (${lifetimeContext.totalTracks} total tracks) ===")
                appendLine("All-time: ${"%.1f".format(lifetimeContext.totalDistanceKm)}km total, avg ${"%.2f".format(lifetimeContext.avgDistanceKm)}km/trip, avg speed ${"%.1f".format(lifetimeContext.avgSpeedKmh)}km/h")
                appendLine("Personal bests: longest ${"%.2f".format(lifetimeContext.bestDistanceKm)}km, fastest ${"%.1f".format(lifetimeContext.bestSpeedKmh)}km/h")
                appendLine("Avg duration: ${lifetimeContext.avgDurationMin}min, avg calories: ${"%.0f".format(lifetimeContext.avgCaloriesPerTrip)}/trip")
                if (lifetimeContext.sameActivityCount > 1) {
                    appendLine("Same activity (${track.activityType}) stats: ${lifetimeContext.sameActivityCount} trips, avg ${"%.1f".format(lifetimeContext.sameActivityAvgSpeedKmh)}km/h, avg ${"%.2f".format(lifetimeContext.sameActivityAvgDistanceKm)}km")
                }
            }

            appendLine()
            appendLine("Reference the actual numbers above.  Call out concrete nuances (pace drops, speed inconsistencies, elevation effort, unusual stops) — never generic praise.  Output the JSON object and nothing else.")
        }
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        return buildString {
            appendLine("Analyze the ${snapshots.size} trips below.  Return ONE JSON object that matches this schema exactly — no markdown, no prose before or after:")
            appendLine("{")
            appendLine("  \"totalDistance\": 0,           // meters, sum across listed trips")
            appendLine("  \"totalCalories\": 0,           // kcal, sum across listed trips")
            appendLine("  \"dominantActivity\": \"\",     // one of STATIONARY|WALKING|RUNNING|CYCLING|DRIVING|FLYING")
            appendLine("  \"weekSummary\": \"\",          // 3–4 sentences citing numbers from the trip list")
            appendLine("  \"improvements\": [\"\"]        // 3–5 short, actionable tips grounded in the listed metrics")
            appendLine("}")
            appendLine()
            appendLine("Rules: Use ONLY the trip data below.  Do not invent metrics not listed.  Output parseable JSON (quoted keys, no trailing commas, no comments).")
            appendLine()
            appendLine("=== TRIPS ===")
            snapshots.forEachIndexed { i, twp ->
                val t = twp.track
                val dur = TimeUnit.MILLISECONDS.toMinutes((t.endTime ?: System.currentTimeMillis()) - t.startTime)
                appendLine("${i+1}. ${t.activityType} ${"%.1f".format(t.distanceMeters/1000)}km ${dur}m ${"%.1f".format(t.avgSpeedKmh)}km/h ${"%.0f".format(t.caloriesBurned)}cal")
            }
            appendLine()
            appendLine("Output the JSON object and nothing else.")
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
}

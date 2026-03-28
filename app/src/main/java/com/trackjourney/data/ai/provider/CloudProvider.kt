package com.trackjourney.data.ai.provider

import android.util.Log
import com.trackjourney.BuildConfig
import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.AiPreferences
import com.trackjourney.data.ai.models.CloudProviderType
import com.trackjourney.data.local.SettingsDataStore
import com.trackjourney.data.model.CloudAiProvider
import com.trackjourney.data.model.TrackWithPoints
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
     */
    suspend fun ensureConfigLoaded() {
        val settings = settingsDataStore.settings.first()

        // Always reload from SettingsDataStore to pick up provider/key changes
        apiKey = settings.cloudAiApiKey.takeIf { it.isNotBlank() }
            ?: aiPreferences.getCloudApiKey()

        providerType = mapSettingsProvider(settings.cloudAiProvider)
        customEndpoint = settings.cloudAiEndpoint
        customModel = settings.cloudAiModel
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

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints): String {
        ensureConfigLoaded()
        if (!isConfigured()) {
            throw IllegalStateException("Cloud AI not configured. Set API key in Settings → AI Engine → Cloud AI.")
        }
        val prompt = buildDailyPrompt(snapshot)
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
                    put("content", "You are a fitness and activity analysis AI. Always respond with valid JSON only, no markdown.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.3)
            put("max_tokens", 1024)
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
            put("max_tokens", 1024)
            put("system", "You are a fitness and activity analysis AI. Always respond with valid JSON only, no markdown.")
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
        // Try to find JSON in a code block first
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n?(\\{[\\s\\S]*?})\\s*\\n?```")
        codeBlockRegex.find(content)?.let { match ->
            return match.groupValues[1].trim()
        }

        // Try to find a raw JSON object
        val jsonStart = content.indexOf('{')
        val jsonEnd = content.lastIndexOf('}')
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            val candidate = content.substring(jsonStart, jsonEnd + 1)
            // Validate it parses as JSON
            try {
                JSONObject(candidate)
                return candidate
            } catch (_: Exception) {
                // Fall through
            }
        }

        // Return as-is and let the caller handle it
        return content
    }

    // ── Prompt builders ─────────────────────────────────────────────────────

    private fun buildDailyPrompt(snapshot: TrackWithPoints): String {
        val track = snapshot.track
        val points = snapshot.points
        val healthData = snapshot.healthData

        val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
        val durationMin = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val durationSec = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60

        val altitudes = points.mapNotNull { it.altitude }
        val elevationGain = computeElevationGain(altitudes)
        val heartRates = points.mapNotNull { it.heartRate } + healthData.mapNotNull { it.heartRate }
        val avgHr = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val maxHr = heartRates.maxOrNull()
        val speeds = points.map { it.speedKmh }.filter { it > 0 }
        val medianSpeed = speeds.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 0f }
        val avgCadence = points.mapNotNull { it.cadence }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val paceMinPerKm = if (track.distanceMeters > 0 && durationMin > 0) {
            (durationMin + durationSec / 60.0) / (track.distanceMeters / 1000.0)
        } else null

        return buildString {
            appendLine("Analyze this GPS-tracked activity. Return JSON: {\"activity\":\"WALKING|RUNNING|CYCLING|DRIVING|FLYING|STATIONARY\",\"confidence\":0.0-1.0,\"summary\":\"2-3 sentences\",\"suggestions\":[\"2-4 tips\"],\"healthInsights\":\"or null\"}")
            appendLine()
            appendLine("Activity: ${track.activityType}")
            appendLine("Distance: ${"%.2f".format(track.distanceMeters / 1000)}km, Duration: ${durationMin}m ${durationSec}s")
            appendLine("Speed: avg ${"%.1f".format(track.avgSpeedKmh)}, max ${"%.1f".format(track.maxSpeedKmh)}, median ${"%.1f".format(medianSpeed)} km/h")
            appendLine("Calories: ${"%.0f".format(track.caloriesBurned)} kcal")
            if (paceMinPerKm != null && track.avgSpeedKmh < 20) appendLine("Pace: ${"%.1f".format(paceMinPerKm)} min/km")
            if (elevationGain > 0) appendLine("Elevation gain: ${"%.0f".format(elevationGain)}m")
            if (avgHr != null) appendLine("Heart Rate: avg $avgHr, max $maxHr bpm")
            if (avgCadence != null) appendLine("Cadence: $avgCadence spm")
            if (track.startPlaceName != null || track.endPlaceName != null) {
                appendLine("Route: ${track.startPlaceName ?: "?"} → ${track.endPlaceName ?: "?"}")
            }
            appendLine("Return valid JSON only.")
        }
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        return buildString {
            appendLine("Analyze ${snapshots.size} trips. Return JSON: {\"totalDistance\":meters,\"totalCalories\":num,\"dominantActivity\":\"...\",\"weekSummary\":\"3-4 sentences\",\"improvements\":[\"3-5 tips\"]}")
            snapshots.forEachIndexed { i, twp ->
                val t = twp.track
                val dur = TimeUnit.MILLISECONDS.toMinutes((t.endTime ?: System.currentTimeMillis()) - t.startTime)
                appendLine("${i+1}. ${t.activityType} ${"%.1f".format(t.distanceMeters/1000)}km ${dur}m ${"%.1f".format(t.avgSpeedKmh)}km/h ${"%.0f".format(t.caloriesBurned)}cal")
            }
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

package com.trackjourney.data.ai.provider

import android.util.Log
import com.trackjourney.BuildConfig
import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.AiPreferences
import com.trackjourney.data.ai.models.CloudProviderType
import com.trackjourney.data.model.TrackWithPoints
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud-based AI provider that sends summarized track data to a cloud API
 * for analysis. Loads the API key and provider type from AiPreferences.
 */
@Singleton
class CloudProvider @Inject constructor(
    private val aiPreferences: AiPreferences
) : AiAnalysisProvider {

    companion object {
        private const val TAG = "CloudProvider"
    }

    override val executionMode: AiExecutionMode = AiExecutionMode.CLOUD
    override val displayName: String = "Cloud AI"

    private var apiKey: String? = null
    private var providerType: CloudProviderType = CloudProviderType.CLAUDE

    suspend fun ensureConfigLoaded() {
        if (apiKey == null) {
            apiKey = aiPreferences.getCloudApiKey()
            val type = aiPreferences.getCloudProviderType()
            providerType = try {
                CloudProviderType.valueOf(type)
            } catch (_: Exception) {
                CloudProviderType.CLAUDE
            }
        }
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
        // TODO: Replace with actual cloud API call using apiKey and providerType
        val response = """{"source": "cloud_ai", "provider": "${providerType.name}", "status": "api_call_pending"}"""
        return response
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        ensureConfigLoaded()
        if (!isConfigured()) {
            throw IllegalStateException("Cloud AI not configured. Set API key in Settings → AI Engine → Cloud AI.")
        }
        val prompt = buildWeeklyPrompt(snapshots)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Prompt [weekly] (${providerType.label}):\n$prompt")
        val response = """{"source": "cloud_ai", "provider": "${providerType.name}", "status": "api_call_pending"}"""
        return response
    }

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

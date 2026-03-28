package com.trackjourney.data.ai.provider

import android.util.Log
import com.trackjourney.BuildConfig
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

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints, lifetimeContext: LifetimeContext?): String {
        val prompt = buildDailyPrompt(snapshot, lifetimeContext)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Prompt [daily]:\n$prompt")
        val response = systemRuntime.runPrompt(prompt)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Response [daily]:\n$response")
        return response
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        val prompt = buildWeeklyPrompt(snapshots)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Prompt [weekly]:\n$prompt")
        val response = systemRuntime.runPrompt(prompt)
        if (BuildConfig.DEBUG) Log.d(TAG, "AI Response [weekly]:\n$response")
        return response
    }

    private fun buildDailyPrompt(snapshot: TrackWithPoints, lifetimeContext: LifetimeContext? = null): String {
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

        // Pace for walking/running
        val paceMinPerKm = if (track.distanceMeters > 0 && durationMin > 0) {
            durationMin.toDouble() / (track.distanceMeters / 1000.0)
        } else null

        return buildString {
            appendLine("You are a fitness and journey analyst. Analyze this GPS-tracked journey data.")
            appendLine("RESPOND WITH ONLY A JSON OBJECT. No other text before or after the JSON.")
            appendLine("Return JSON with keys: activity (STATIONARY <0.5km/h, WALKING <7, RUNNING 7-15, CYCLING 15-40, DRIVING 40-200, FLYING >200 km/h), confidence (0.0-1.0), summary (4-5 sentences analyzing performance, terrain, pace consistency, speed patterns, and nuances with specific numbers), suggestions (3-5 actionable tips referencing actual metrics), healthInsights (heart rate zone analysis and fitness observations if HR data present, else null), lifetimeInsights (if lifetime data provided: 2-3 sentences comparing this trip to historical averages and personal bests, else null).")
            appendLine()
            appendLine("Journey:")
            appendLine("- Activity: ${track.activityType}")
            appendLine("- Distance: ${"%.2f".format(track.distanceMeters / 1000)}km")
            appendLine("- Duration: ${durationMin} min")
            appendLine("- Avg Speed: ${"%.1f".format(track.avgSpeedKmh)} km/h, Max: ${"%.1f".format(track.maxSpeedKmh)} km/h")
            if (paceMinPerKm != null && track.avgSpeedKmh < 20) {
                appendLine("- Pace: ${"%.1f".format(paceMinPerKm)} min/km")
            }
            appendLine("- Calories: ${"%.0f".format(track.caloriesBurned)} kcal")

            if (minAlt != null && maxAlt != null) {
                appendLine("- Elevation: ${"%.0f".format(minAlt)}-${"%.0f".format(maxAlt)}m, gain: ${"%.0f".format(elevationGain)}m")
            }
            if (avgHr != null) {
                appendLine("- Heart Rate: avg $avgHr, max $maxHr bpm")
            }
            if (avgCadence != null) {
                appendLine("- Cadence: $avgCadence spm")
            }
            if (track.startPlaceName != null || track.endPlaceName != null) {
                appendLine("- Route: ${track.startPlaceName ?: "?"} → ${track.endPlaceName ?: "?"}")
            }
            if (activitySegments.size > 1) {
                appendLine("- Segments: ${activitySegments.joinToString { "${it.key}(${if (points.isNotEmpty()) (it.value * 100) / points.size else 0}%)" }}")
            }
            if (track.rideCost != null) {
                appendLine("- Ride Cost: ${"%.2f".format(track.rideCost)}")
            }

            // Lifetime context
            if (lifetimeContext != null && lifetimeContext.totalTracks >= 2) {
                appendLine()
                appendLine("Lifetime Stats (${lifetimeContext.totalTracks} total tracks):")
                appendLine("- All-time: ${"%.1f".format(lifetimeContext.totalDistanceKm)}km total, avg ${"%.2f".format(lifetimeContext.avgDistanceKm)}km/trip, avg speed ${"%.1f".format(lifetimeContext.avgSpeedKmh)}km/h")
                appendLine("- Bests: longest ${"%.2f".format(lifetimeContext.bestDistanceKm)}km, fastest ${"%.1f".format(lifetimeContext.bestSpeedKmh)}km/h")
                appendLine("- Avg duration: ${lifetimeContext.avgDurationMin}min, avg calories: ${"%.0f".format(lifetimeContext.avgCaloriesPerTrip)}/trip")
                if (lifetimeContext.sameActivityCount > 1) {
                    appendLine("- Same activity (${track.activityType}): ${lifetimeContext.sameActivityCount} trips, avg ${"%.1f".format(lifetimeContext.sameActivityAvgSpeedKmh)}km/h, avg ${"%.2f".format(lifetimeContext.sameActivityAvgDistanceKm)}km")
                }
            }

            appendLine()
            appendLine("Be specific — reference the actual numbers. Identify nuances like pace drops, speed inconsistencies, or unusual patterns.")
            appendLine("IMPORTANT: Output ONLY valid JSON. No explanations, no markdown, no text before or after the JSON object.")
        }
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        return buildString {
            appendLine("Analyze ${snapshots.size} GPS-tracked journeys from this week. Compare sessions, identify trends, and suggest improvements.")
            appendLine("Return JSON: totalDistance (meters), totalCalories, dominantActivity, weekSummary (3-4 sentences comparing sessions and noting patterns), improvements (3-4 specific data-backed suggestions).")
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
            appendLine("Totals: ${"%.2f".format(totalDist / 1000)}km, ${totalDurationMin}min, ${"%.0f".format(totalCal)}cal")
            appendLine()
            appendLine("Compare best vs weakest session. Suggest specific improvements using actual numbers. Return valid JSON only.")
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

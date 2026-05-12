package com.trackmyjourney.data.ai.provider

import android.util.Log
import com.trackmyjourney.BuildConfig
import com.trackmyjourney.data.ai.models.AiExecutionMode
import com.trackmyjourney.data.ai.runtime.SystemAiRuntimeAdapter
import com.trackmyjourney.data.model.TrackWithPoints
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

        val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
        val durationMin = TimeUnit.MILLISECONDS.toMinutes(durationMs)

        // Elevation
        val altitudes = points.mapNotNull { it.altitude }
        val elevationGain = computeElevationGain(altitudes)
        val elevationLoss = computeElevationLoss(altitudes)
        val minAlt = altitudes.minOrNull()
        val maxAlt = altitudes.maxOrNull()

        // Speed
        val speeds = points.map { it.speedKmh }.filter { it > 0 }
        val medianSpeed = speeds.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 0f }

        // Speed consistency
        val speedStdDev = if (speeds.size >= 2) {
            val mean = speeds.average()
            kotlin.math.sqrt(speeds.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0f

        // Cadence
        val cadences = points.mapNotNull { it.cadence }
        val avgCadence = cadences.takeIf { it.isNotEmpty() }?.average()?.toInt()

        // Activity segments
        val activitySegments = points.groupBy { it.activityType }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        // Pace for walking/running
        val paceMinPerKm = if (track.distanceMeters > 0 && durationMin > 0) {
            durationMin.toDouble() / (track.distanceMeters / 1000.0)
        } else null

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
        val stopMin = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(totalStopMs)

        // Time of day
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = track.startTime
        val startHour = cal.get(java.util.Calendar.HOUR_OF_DAY)

        // GPS accuracy
        val accuracies = points.mapNotNull { it.accuracy }
        val avgAccuracy = accuracies.takeIf { it.isNotEmpty() }?.average()
        val satellites = points.mapNotNull { it.satellitesUsed }
        val avgSatellites = satellites.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val inaccuratePoints = points.count { !it.isAccurate }

        // Bearing
        val bearings = points.mapNotNull { it.bearing }
        val bearingStdDev = if (bearings.size >= 2) {
            val mean = bearings.average()
            kotlin.math.sqrt(bearings.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else null

        return buildString {
            appendLine("Analyze the GPS-tracked journey below.  Return ONE JSON object exactly matching this shape — no markdown, no code fences, no comments, no prose before or after:")
            appendLine("{\"activity\":\"WALKING\",\"confidence\":0.85,\"summary\":\"...\",\"suggestions\":[\"...\",\"...\",\"...\"],\"lifetimeInsights\":null}")
            appendLine()
            appendLine("Field rules:")
            appendLine("- activity: one of WALKING, RUNNING, CYCLING, DRIVING, FLYING, STATIONARY (a single word, no pipes, no speed ranges).")
            appendLine("- confidence: a number in [0.0, 1.0].")
            appendLine("- summary: 3-5 sentences (~60-110 words) citing numbers from Journey.")
            appendLine("- suggestions: an array of 3-5 short, actionable tips.")
            appendLine("- lifetimeInsights: 2-3 sentences ONLY if a Lifetime Stats block is present below, otherwise the literal null (never the string \"null\").")
            appendLine()
            appendLine("Data rules:")
            appendLine("- Use ONLY the numbers in Journey / Lifetime Stats.  Do not invent metrics.")
            appendLine("- Speed thresholds (km/h): STATIONARY <0.5, WALKING 0.5-7, RUNNING 7-15, CYCLING 15-35, DRIVING 35-200, FLYING >=200.")
            appendLine("- If a `User-set activity` is shown, return that exact value as activity.")
            appendLine("- Round numbers in prose to at most 1 decimal place.")
            appendLine()
            appendLine("JSON rules: double-quoted keys and strings, no trailing commas, no comments, no extra keys, no text outside the object.")
            appendLine()
            appendLine("Journey:")
            if (track.customActivityType != null) {
                appendLine("- User-set activity: ${track.customActivityType} (treat this as the correct activity type)")
                appendLine("- Auto-detected activity: ${track.activityType}")
            } else {
                appendLine("- Activity: ${track.activityType}")
            }
            appendLine("- Time: ${startHour}:00 start, Duration: ${durationMin} min")
            appendLine("- Distance: ${"%.2f".format(track.distanceMeters / 1000)}km, GPS points: ${points.size}")
            appendLine("- Speed: avg ${"%.1f".format(track.avgSpeedKmh)}, max ${"%.1f".format(track.maxSpeedKmh)}, median ${"%.1f".format(medianSpeed)}, stdDev ${"%.1f".format(speedStdDev)} km/h")
            if (paceMinPerKm != null && track.avgSpeedKmh < 20) {
                appendLine("- Pace: ${"%.1f".format(paceMinPerKm)} min/km")
            }
            appendLine("- Calories: ${"%.0f".format(track.caloriesBurned)} kcal")

            if (minAlt != null && maxAlt != null) {
                appendLine("- Elevation: ${"%.0f".format(minAlt)}-${"%.0f".format(maxAlt)}m, gain: ${"%.0f".format(elevationGain)}m, loss: ${"%.0f".format(elevationLoss)}m")
            }
            if (avgCadence != null) {
                appendLine("- Cadence: avg $avgCadence, min ${cadences.minOrNull()}, max ${cadences.maxOrNull()} spm")
            }
            if (stopCount > 0) {
                append("- Stops: $stopCount")
                if (stopMin > 0) append(", total stop time: ${stopMin}min")
                appendLine()
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
            if (bearingStdDev != null) {
                appendLine("- Bearing variability: ${"%.0f".format(bearingStdDev)}° (low = straight, high = turns)")
            }
            if (avgAccuracy != null) appendLine("- GPS accuracy: avg ${"%.1f".format(avgAccuracy)}m")
            if (avgSatellites != null) appendLine("- GPS satellites: avg $avgSatellites")
            if (inaccuratePoints > 0) appendLine("- Inaccurate GPS points: $inaccuratePoints of ${points.size}")
            if (track.batteryStart != null || track.batteryEnd != null) {
                appendLine("- Phone battery: ${track.batteryStart ?: "?"}% → ${track.batteryEnd ?: "?"}%")
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
            appendLine("Reference the actual numbers above.  Call out concrete nuances (pace drops, speed inconsistencies, elevation effort, unusual stops) — never generic praise.  Output the JSON object and nothing else.")
        }
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        return buildString {
            appendLine("Analyze the ${snapshots.size} trips below.  Return ONE JSON object exactly matching this shape — no markdown, no comments, no prose before or after:")
            appendLine("{\"totalDistance\":1000,\"totalCalories\":120,\"dominantActivity\":\"WALKING\",\"weekSummary\":\"...\",\"improvements\":[\"...\",\"...\",\"...\"]}")
            appendLine()
            appendLine("Field rules:")
            appendLine("- totalDistance: meters, sum across listed trips.")
            appendLine("- totalCalories: kcal, sum across listed trips.")
            appendLine("- dominantActivity: one of WALKING, RUNNING, CYCLING, DRIVING, FLYING, STATIONARY (a single word).")
            appendLine("- weekSummary: 3-4 sentences citing numbers from the trip list.")
            appendLine("- improvements: an array of 3-5 short, actionable tips.")
            appendLine()
            appendLine("Data rule: Use ONLY the trip data below.")
            appendLine("JSON rules: double-quoted keys and strings, no trailing commas, no comments, no extra keys, no text outside the object.")
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

                append("${i + 1}. ${track.activityType}: ${"%.2f".format(track.distanceMeters / 1000)}km, ${durationMin}min, ${"%.1f".format(track.avgSpeedKmh)}km/h, ${"%.0f".format(track.caloriesBurned)}cal")
                if (elevGain > 0) append(", elev+${"%.0f".format(elevGain)}m")
                if (track.startPlaceName != null) append(", from:${track.startPlaceName}")
                appendLine()
            }

            val totalDurationMin = TimeUnit.MILLISECONDS.toMinutes(totalDurationMs)
            appendLine()
            appendLine("Totals: ${"%.2f".format(totalDist / 1000)}km, ${totalDurationMin}min, ${"%.0f".format(totalCal)}cal")
            appendLine()
            appendLine("Compare strongest vs weakest session using the numbers above.  Output the JSON object and nothing else.")
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

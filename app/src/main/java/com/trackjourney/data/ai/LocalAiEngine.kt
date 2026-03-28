package com.trackjourney.data.ai

import android.content.Context
import android.util.Log
import com.trackjourney.data.location.LocationTracker
import com.trackjourney.data.location.MotionSensorManager
import com.trackjourney.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Local AI engine that runs entirely on-device.
 *
 * Two analysis modes:
 * 1. RULE-BASED CLASSIFIER — fast, real-time activity detection from speed/altitude patterns
 * 2. TFLite MODEL — optional ML model for more nuanced classification
 *
 * The rule-based engine works out of the box. For TFLite, place the model file
 * "activity_classifier.tflite" in app/src/main/assets/.
 * You can train your own model or use Google's Activity Recognition TFLite model.
 */
class LocalAiEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "LocalAiEngine"
        // Speed thresholds (km/h) — tuned from real-world data
        private const val STATIONARY_MAX = 0.5f
        private const val WALK_MAX = 7.0f
        private const val RUN_MAX = 15.0f
        private const val CYCLE_MAX = 40.0f
        private const val DRIVE_MAX = 250.0f

        // Altitude change thresholds (meters per minute)
        private const val FLYING_ALTITUDE_RATE = 10.0f  // rapid altitude gain → flying

        // Analysis window
        private const val SEGMENT_WINDOW_SIZE = 10  // points per analysis segment
    }

    private val modelLoaded = false // TFLite classifier removed; rule-based only

    // ═══════════════════════════════════════════════════════
    //  REAL-TIME ACTIVITY DETECTION (single point)
    // ═══════════════════════════════════════════════════════

    /**
     * Real-time activity detection combining GPS speed with physical sensor data.
     *
     * When the accelerometer/gyroscope/step-detector indicate the device is stationary
     * but GPS reports movement (GPS drift), the sensor data overrides and returns STATIONARY.
     *
     * @param motionState Optional physical sensor reading. Null means no sensor data available.
     */
    fun detectActivity(
        speedKmh: Float,
        altitude: Double?,
        previousAltitude: Double?,
        motionState: MotionSensorManager.MotionState? = null
    ): ActivityType {
        // ── Sensor-based GPS drift rejection ──
        // If physical sensors say "not moving" with high confidence,
        // override GPS speed which is likely drift.
        // IMPORTANT: Skip this override when vehicle motion is detected (no steps
        // in a car is normal) or when the device IS moving (sensors confirm motion).
        if (motionState != null
            && !motionState.isDeviceMoving
            && !motionState.vehicleMotionDetected
            && motionState.motionConfidence < 0.2f
        ) {
            // Sensors are very confident the device is stationary.
            // GPS drift can report up to ~15 km/h phantom speed.
            // Only trust GPS over sensors for high speeds (vehicle/flying)
            // where the phone might not shake much (smooth ride).
            if (speedKmh < CYCLE_MAX) {
                Log.d(TAG, "Sensor override: GPS says ${speedKmh}km/h but sensors say stationary " +
                        "(accel=${motionState.accelerationMagnitude}, gyro=${motionState.rotationRate}, " +
                        "step=${motionState.stepDetected}, conf=${motionState.motionConfidence})")
                return ActivityType.STATIONARY
            }
        }

        // ── Sensor-assisted low-speed disambiguation ──
        // GPS reports 0.5-7 km/h but could be drift or real walking.
        // Use step detector as tie-breaker — but only when step permission is
        // granted.  Without ACTIVITY_RECOGNITION, we cannot detect steps so we
        // must trust the GPS speed + accelerometer confidence instead.
        if (motionState != null && speedKmh in STATIONARY_MAX..WALK_MAX) {
            if (motionState.vehicleMotionDetected) {
                // Vehicle is creeping forward (parking lot, traffic) — not stationary
            } else if (motionState.isDeviceMoving) {
                // Sensors confirm the device is physically moving — trust GPS speed
            } else if (!motionState.stepPermissionGranted) {
                // No step data available — only override if accel/gyro confidence
                // is very low (stricter threshold to avoid false stationary)
                if (motionState.motionConfidence < 0.15f) {
                    return ActivityType.STATIONARY
                }
            } else if (!motionState.stepDetected && motionState.motionConfidence < 0.35f) {
                // Steps available but none detected + low motion → GPS drift
                return ActivityType.STATIONARY
            }
        }

        // Check for flying based on altitude rate of change
        if (altitude != null && previousAltitude != null) {
            val altitudeChange = abs(altitude - previousAltitude)
            if (altitudeChange > FLYING_ALTITUDE_RATE && speedKmh > DRIVE_MAX) {
                return ActivityType.FLYING
            }
        }

        return when {
            speedKmh < STATIONARY_MAX -> ActivityType.STATIONARY
            speedKmh < WALK_MAX       -> ActivityType.WALKING
            speedKmh < RUN_MAX        -> ActivityType.RUNNING
            speedKmh < CYCLE_MAX      -> ActivityType.CYCLING
            speedKmh < DRIVE_MAX      -> ActivityType.DRIVING
            else                       -> ActivityType.FLYING
        }
    }

    // ═══════════════════════════════════════════════════════
    //  FULL TRACK ANALYSIS (post-recording)
    // ═══════════════════════════════════════════════════════

    suspend fun analyzeTrack(
        points: List<TrackPoint>,
        healthData: List<HealthData>
    ): AiAnalysis = withContext(Dispatchers.Default) {
        if (points.isEmpty()) {
            return@withContext AiAnalysis(
                trackId = "",
                detectedActivity = ActivityType.UNKNOWN,
                confidence = 0f,
                summary = "No data to analyze."
            )
        }

        val trackId = points.first().trackId

        // 1. Segment-based activity detection
        val segments = detectActivitySegments(points)

        // 2. Determine dominant activity
        val dominantActivity = findDominantActivity(segments)

        // 3. Calculate statistics
        val stats = calculateTrackStats(points)

        // 4. Generate health insights
        val healthInsights = analyzeHealthData(healthData, stats)

        // 5. Generate trip suggestions
        val suggestions = generateSuggestions(stats, segments, healthData)

        // 6. Build summary
        val summary = buildSummary(stats, dominantActivity, segments)

        AiAnalysis(
            trackId = trackId,
            detectedActivity = dominantActivity.activity,
            confidence = dominantActivity.confidence,
            summary = summary,
            suggestions = suggestions.joinToString("|"),
            healthInsights = healthInsights,
            segmentActivities = buildSegmentJson(segments)
        )
    }

    // ─── SEGMENTED ACTIVITY DETECTION ───────────────────

    private fun detectActivitySegments(points: List<TrackPoint>): List<DetectedSegment> {
        if (points.size < 2) return listOf(
            DetectedSegment(0, points.size - 1, ActivityType.UNKNOWN, 0.5f)
        )

        val segments = mutableListOf<DetectedSegment>()
        var segmentStart = 0
        var currentActivity = classifyPointGroup(points.take(SEGMENT_WINDOW_SIZE))

        for (i in SEGMENT_WINDOW_SIZE until points.size step SEGMENT_WINDOW_SIZE) {
            val window = points.subList(i, minOf(i + SEGMENT_WINDOW_SIZE, points.size))
            val windowActivity = classifyPointGroup(window)

            if (windowActivity.activity != currentActivity.activity) {
                segments.add(DetectedSegment(
                    startIndex = segmentStart,
                    endIndex = i - 1,
                    activity = currentActivity.activity,
                    confidence = currentActivity.confidence
                ))
                segmentStart = i
                currentActivity = windowActivity
            }
        }

        // Last segment
        segments.add(DetectedSegment(
            startIndex = segmentStart,
            endIndex = points.size - 1,
            activity = currentActivity.activity,
            confidence = currentActivity.confidence
        ))

        return mergeShortSegments(segments, points)
    }

    private data class ClassificationResult(val activity: ActivityType, val confidence: Float)

    private fun classifyPointGroup(points: List<TrackPoint>): ClassificationResult {
        if (points.isEmpty()) return ClassificationResult(ActivityType.UNKNOWN, 0f)

        val speeds = points.map { it.speedKmh }
        val avgSpeed = speeds.average().toFloat()
        val maxSpeed = speeds.maxOrNull() ?: 0f
        val speedVariance = calculateVariance(speeds)



        // Rule-based classification with confidence scoring
        return classifyWithRules(avgSpeed, maxSpeed, speedVariance, points)
    }

    private fun classifyWithRules(
        avgSpeed: Float,
        maxSpeed: Float,
        speedVariance: Float,
        points: List<TrackPoint>
    ): ClassificationResult {
        // Check altitude for flying detection
        val altitudes = points.mapNotNull { it.altitude }
        val hasRapidAltitudeChange = if (altitudes.size >= 2) {
            val altRate = abs(altitudes.last() - altitudes.first()) /
                ((points.last().timestamp - points.first().timestamp) / 60000.0)
            altRate > FLYING_ALTITUDE_RATE
        } else false

        val activity: ActivityType
        var confidence: Float

        when {
            avgSpeed > DRIVE_MAX || (avgSpeed > 100 && hasRapidAltitudeChange) -> {
                activity = ActivityType.FLYING
                confidence = if (hasRapidAltitudeChange) 0.95f else 0.80f
            }
            avgSpeed > CYCLE_MAX -> {
                activity = ActivityType.DRIVING
                // High variance in speed suggests stop-and-go driving
                confidence = if (speedVariance > 100) 0.90f else 0.85f
            }
            avgSpeed > RUN_MAX -> {
                activity = ActivityType.CYCLING
                // Cycling has more consistent speed than driving
                confidence = if (speedVariance < 50) 0.85f else 0.70f
            }
            avgSpeed > WALK_MAX -> {
                activity = ActivityType.RUNNING
                confidence = 0.80f
            }
            avgSpeed > STATIONARY_MAX -> {
                activity = ActivityType.WALKING
                confidence = 0.85f
            }
            else -> {
                activity = ActivityType.STATIONARY
                confidence = 0.90f
            }
        }

        // Reduce confidence if speed is near boundaries
        val boundaries = listOf(STATIONARY_MAX, WALK_MAX, RUN_MAX, CYCLE_MAX, DRIVE_MAX)
        for (boundary in boundaries) {
            if (abs(avgSpeed - boundary) < 2.0f) {
                confidence *= 0.85f
            }
        }

        return ClassificationResult(activity, confidence.coerceIn(0f, 1f))
    }

    /**
     * Merge very short segments into neighbors to reduce noise.
     */
    private fun mergeShortSegments(
        segments: List<DetectedSegment>,
        points: List<TrackPoint>
    ): List<DetectedSegment> {
        if (segments.size <= 1) return segments

        val merged = mutableListOf<DetectedSegment>()
        var current = segments.first()

        for (i in 1 until segments.size) {
            val next = segments[i]
            val currentDuration = if (current.endIndex < points.size && current.startIndex < points.size) {
                points[current.endIndex].timestamp - points[current.startIndex].timestamp
            } else 0L

            // Merge segments shorter than 30 seconds into previous
            if (currentDuration < 30_000L) {
                current = current.copy(
                    endIndex = next.endIndex,
                    activity = if (current.confidence > next.confidence) current.activity else next.activity,
                    confidence = maxOf(current.confidence, next.confidence)
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    // ─── TRACK STATISTICS ──────────────────────────────

    private data class TrackStats(
        val totalDistanceKm: Double,
        val durationMinutes: Double,
        val avgSpeedKmh: Double,
        val maxSpeedKmh: Double,
        val totalElevationGain: Double,
        val totalElevationLoss: Double,
        val averageAccuracy: Float
    )

    private fun calculateTrackStats(points: List<TrackPoint>): TrackStats {
        var totalDistance = 0.0
        var elevGain = 0.0
        var elevLoss = 0.0

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]

            totalDistance += haversineDistance(
                prev.latitude, prev.longitude,
                curr.latitude, curr.longitude
            )

            if (prev.altitude != null && curr.altitude != null) {
                val diff = curr.altitude - prev.altitude
                if (diff > 0) elevGain += diff else elevLoss += abs(diff)
            }
        }

        val durationMs = points.last().timestamp - points.first().timestamp
        val durationMin = durationMs / 60_000.0
        val avgSpeed = if (durationMin > 0) (totalDistance / 1000.0) / (durationMin / 60.0) else 0.0

        return TrackStats(
            totalDistanceKm = totalDistance / 1000.0,
            durationMinutes = durationMin,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = LocationTracker.percentileSpeed(points.map { it.speedKmh }).toDouble(),
            totalElevationGain = elevGain,
            totalElevationLoss = elevLoss,
            averageAccuracy = points.mapNotNull { it.accuracy }.average().toFloat()
        )
    }

    // ─── HEALTH ANALYSIS ────────────────────────────────

    private fun analyzeHealthData(healthData: List<HealthData>, stats: TrackStats): String? {
        if (healthData.isEmpty()) return null

        val heartRates = healthData.mapNotNull { it.heartRate }
        val cadenceValues = healthData.mapNotNull { it.cadence }

        val insights = mutableListOf<String>()

        if (heartRates.isNotEmpty()) {
            val avgHr = heartRates.average().toInt()
            val maxHr = heartRates.max()
            val minHr = heartRates.min()

            insights.add("Heart Rate: avg $avgHr bpm, range $minHr-$maxHr bpm")

            // Heart rate zone analysis
            val zone1 = heartRates.count { it < 100 } * 100 / heartRates.size  // Recovery
            val zone2 = heartRates.count { it in 100..119 } * 100 / heartRates.size  // Fat burn
            val zone3 = heartRates.count { it in 120..139 } * 100 / heartRates.size  // Aerobic
            val zone4 = heartRates.count { it in 140..159 } * 100 / heartRates.size  // Threshold
            val zone5 = heartRates.count { it >= 160 } * 100 / heartRates.size  // Anaerobic

            if (zone3 + zone4 > 50) {
                insights.add("${ zone3 + zone4 }% of time in aerobic/threshold zones (HR 120-159) — effective for building endurance")
            }
            if (zone5 > 20) {
                insights.add("${zone5}% of time in high-intensity zone (HR 160+) — ensure adequate recovery between sessions")
            }

            when {
                avgHr > 170 -> insights.add("Very high average HR of $avgHr bpm — this intensity level is unsustainable for long sessions. Consider slowing pace to keep HR below 150.")
                avgHr in 150..170 -> insights.add("Average HR of $avgHr bpm indicates high-intensity effort. This is good for short intervals but aim for 120-140 bpm for longer endurance sessions.")
                avgHr in 120..149 -> insights.add("Average HR of $avgHr bpm — solid aerobic training zone for cardiovascular improvement")
                avgHr in 100..119 -> insights.add("Average HR of $avgHr bpm — light activity zone, good for recovery days or warm-ups")
                avgHr < 60 && stats.avgSpeedKmh > 5 -> insights.add("Resting-level HR of $avgHr bpm during activity — indicates excellent cardiovascular fitness")
            }
        }

        if (cadenceValues.isNotEmpty()) {
            val avgCadence = cadenceValues.average().toInt()
            insights.add("Cadence: avg $avgCadence steps/min")

            when {
                avgCadence in 170..185 -> insights.add("Cadence of $avgCadence spm is in the optimal range for efficient running form and injury prevention")
                avgCadence < 160 && stats.avgSpeedKmh > 8 -> insights.add("Cadence of $avgCadence spm is low for your speed of ${"%.1f".format(stats.avgSpeedKmh)} km/h — try shorter, quicker steps (aim for 170+) to reduce impact forces")
                avgCadence > 190 -> insights.add("High cadence of $avgCadence spm — efficient for speed work, but ensure stride length isn't too short for your target pace")
                avgCadence < 150 -> insights.add("Cadence of $avgCadence spm is quite low — increasing to 160-170 can improve efficiency and reduce joint stress")
            }
        }

        return insights.joinToString("\n")
    }

    // ─── TRIP SUGGESTIONS ───────────────────────────────

    private fun generateSuggestions(
        stats: TrackStats,
        segments: List<DetectedSegment>,
        healthData: List<HealthData>
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Distance-based suggestions with specific metrics
        when {
            stats.totalDistanceKm < 0.5 -> {
                suggestions.add("You covered ${"%.1f".format(stats.totalDistanceKm)}km — try gradually building up to 1-2km sessions to see measurable fitness gains")
            }
            stats.totalDistanceKm < 1 -> {
                suggestions.add("${"%.1f".format(stats.totalDistanceKm)}km is a good start — adding just 500m more per session can meaningfully improve cardiovascular endurance")
            }
            stats.totalDistanceKm in 1.0..5.0 -> {
                suggestions.add("Solid ${"%.1f".format(stats.totalDistanceKm)}km session at ${"%.1f".format(stats.avgSpeedKmh)} km/h — try varying your pace with 1-2 minute faster intervals to build speed")
            }
            stats.totalDistanceKm in 5.0..20.0 -> {
                suggestions.add("Strong ${"%.1f".format(stats.totalDistanceKm)}km effort — at your average of ${"%.1f".format(stats.avgSpeedKmh)} km/h, try maintaining a steady pace for the full distance to build endurance")
            }
            stats.totalDistanceKm > 20 -> {
                suggestions.add("${"%.1f".format(stats.totalDistanceKm)}km is a serious distance — plan nutrition stops every 45-60 min and ensure 48h recovery before your next long session")
            }
        }

        // Speed variation and multi-activity suggestions
        val hasMultipleActivities = segments.map { it.activity }.distinct().size > 1
        if (hasMultipleActivities) {
            val activities = segments.map { it.activity }.distinct().joinToString(" + ") {
                it.name.lowercase().replaceFirstChar { c -> c.uppercase() }
            }
            suggestions.add("Mixed-activity session ($activities) — this cross-training approach builds overall fitness and reduces overuse injury risk")
        }

        // Elevation suggestions with specifics
        when {
            stats.totalElevationGain > 200 -> {
                suggestions.add("${stats.totalElevationGain.toInt()}m of climbing is substantial — this builds leg strength and boosts calorie burn by ~30% vs flat routes")
            }
            stats.totalElevationGain > 100 -> {
                suggestions.add("${stats.totalElevationGain.toInt()}m elevation gain adds good training variety — hilly routes strengthen legs and improve endurance")
            }
        }

        // Time-based suggestions with context
        when {
            stats.durationMinutes < 10 -> {
                suggestions.add("${stats.durationMinutes.toInt()} minutes is very short — if time is limited, try higher intensity (faster pace) to maximize benefit per minute")
            }
            stats.durationMinutes < 30 -> {
                suggestions.add("${stats.durationMinutes.toInt()}-minute session — try building towards 30+ minutes, as sustained activity beyond 20 min shifts your body into fat-burning mode")
            }
            stats.durationMinutes > 120 -> {
                suggestions.add("${stats.durationMinutes.toInt()}-minute session — for activities over 2 hours, consider pacing more conservatively and adding a rest day after")
            }
        }

        // Health-based suggestions with specifics
        val avgHr = healthData.mapNotNull { it.heartRate }.average().takeIf { !it.isNaN() }
        if (avgHr != null) {
            when {
                avgHr > 170 -> suggestions.add("Average HR of ${avgHr.toInt()} bpm is very high — try alternating 2 min easy / 1 min hard to build fitness while managing intensity")
                avgHr > 150 -> suggestions.add("Average HR of ${avgHr.toInt()} bpm — try slowing your pace slightly to stay in the 130-150 bpm zone for better aerobic development")
                avgHr < 100 && stats.avgSpeedKmh > 5 -> suggestions.add("Low HR of ${avgHr.toInt()} bpm suggests this intensity is easy for you — try adding speed intervals or hills to challenge your cardiovascular system")
            }
        }

        return suggestions.take(5) // Max 5 suggestions
    }

    // ─── SUMMARY BUILDER ────────────────────────────────

    private fun buildSummary(
        stats: TrackStats,
        dominant: ClassificationResult,
        segments: List<DetectedSegment>
    ): String {
        val activityName = dominant.activity.name.lowercase().replaceFirstChar { it.uppercase() }
        val distance = String.format("%.2f", stats.totalDistanceKm)
        val duration = formatDuration(stats.durationMinutes)
        val avgSpeed = String.format("%.1f", stats.avgSpeedKmh)
        val confidence = (dominant.confidence * 100).toInt()

        // Pace for walking/running
        val paceStr = if (stats.totalDistanceKm > 0 && stats.avgSpeedKmh < 20) {
            val paceMinPerKm = stats.durationMinutes / stats.totalDistanceKm
            String.format("%.1f min/km", paceMinPerKm)
        } else null

        val sb = StringBuilder()
        sb.appendLine("$activityName — ${distance}km in $duration")
        sb.appendLine("Avg Speed: ${avgSpeed} km/h | Max: ${String.format("%.1f", stats.maxSpeedKmh)} km/h")
        if (paceStr != null) {
            sb.appendLine("Pace: $paceStr")
        }

        if (stats.totalElevationGain > 0) {
            sb.appendLine("Elevation: ↑${stats.totalElevationGain.toInt()}m ↓${stats.totalElevationLoss.toInt()}m")
        }

        // Performance commentary
        when (dominant.activity) {
            ActivityType.WALKING -> when {
                stats.avgSpeedKmh > 6 -> sb.appendLine("Fast-paced walk — great for cardiovascular health")
                stats.avgSpeedKmh > 4.5 -> sb.appendLine("Brisk walking pace — good moderate-intensity exercise")
                else -> sb.appendLine("Casual walking pace — good for recovery and daily movement")
            }
            ActivityType.RUNNING -> when {
                stats.avgSpeedKmh > 12 -> sb.appendLine("Strong running pace — competitive-level effort")
                stats.avgSpeedKmh > 9 -> sb.appendLine("Solid running pace — good aerobic training")
                else -> sb.appendLine("Easy running pace — ideal for building base endurance")
            }
            ActivityType.CYCLING -> when {
                stats.avgSpeedKmh > 25 -> sb.appendLine("Fast cycling — strong effort on the road")
                stats.avgSpeedKmh > 18 -> sb.appendLine("Good cycling pace — effective workout")
                else -> sb.appendLine("Leisurely cycling — good for active recovery")
            }
            else -> {}
        }

        if (confidence < 70) {
            sb.appendLine("Note: Activity detection confidence is $confidence% — the journey may include mixed activities")
        }

        if (segments.size > 1) {
            sb.appendLine("\nActivity Breakdown:")
            segments.forEach { seg ->
                val name = seg.activity.name.lowercase().replaceFirstChar { it.uppercase() }
                val segPoints = seg.endIndex - seg.startIndex + 1
                sb.appendLine("  • $name ($segPoints points, ${(seg.confidence * 100).toInt()}% confidence)")
            }
        }

        return sb.toString()
    }

    // ─── BEST TRIPS SUGGESTION ENGINE ───────────────────

    /**
     * Analyze historical tracks to suggest best trips.
     */
    suspend fun suggestBestTrips(
        allTracks: List<TrackWithPoints>
    ): List<TripSuggestion> = withContext(Dispatchers.Default) {
        if (allTracks.size < 2) return@withContext listOf(
            TripSuggestion(
                title = "Keep Tracking!",
                description = "Record more trips to get AI-powered route suggestions.",
                score = 0f
            )
        )

        val suggestions = mutableListOf<TripSuggestion>()

        // Find most efficient routes (best distance/time ratio)
        val walkingTracks = allTracks.filter {
            it.track.activityType == ActivityType.WALKING && it.points.size > 10
        }
        if (walkingTracks.isNotEmpty()) {
            val bestWalk = walkingTracks.maxByOrNull { it.track.distanceMeters / maxOf(1.0, it.track.avgSpeedKmh) }
            bestWalk?.let {
                suggestions.add(TripSuggestion(
                    title = "🚶 Best Walking Route",
                    description = "${String.format("%.1f", it.track.distanceMeters / 1000)}km at ${String.format("%.1f", it.track.avgSpeedKmh)} km/h — ${it.track.name.ifEmpty { "Unnamed route" }}",
                    score = 0.9f,
                    trackId = it.track.id
                ))
            }
        }

        // Find most scenic (highest elevation change) routes
        val scenicTrack = allTracks
            .filter { it.points.size > 10 }
            .maxByOrNull { track ->
                val alts = track.points.mapNotNull { it.altitude }
                if (alts.size >= 2) alts.max() - alts.min() else 0.0
            }
        scenicTrack?.let {
            suggestions.add(TripSuggestion(
                title = "🏔️ Most Scenic Route",
                description = "Greatest elevation variety — ${it.track.name.ifEmpty { "Unnamed route" }}",
                score = 0.85f,
                trackId = it.track.id
            ))
        }

        // Find healthiest trip (best heart rate data)
        val healthyTrips = allTracks.filter { it.track.avgHeartRate != null }
        if (healthyTrips.isNotEmpty()) {
            val bestCardio = healthyTrips.minByOrNull { abs((it.track.avgHeartRate ?: 0) - 130) }
            bestCardio?.let {
                suggestions.add(TripSuggestion(
                    title = "❤️ Best Cardio Route",
                    description = "Optimal heart rate zone (avg ${it.track.avgHeartRate} bpm) — ${it.track.name.ifEmpty { "Unnamed route" }}",
                    score = 0.88f,
                    trackId = it.track.id
                ))
            }
        }

        // Longest trip
        val longest = allTracks.maxByOrNull { it.track.distanceMeters }
        longest?.let {
            suggestions.add(TripSuggestion(
                title = "📏 Longest Trip",
                description = "${String.format("%.1f", it.track.distanceMeters / 1000)}km — ${it.track.name.ifEmpty { "Unnamed route" }}",
                score = 0.75f,
                trackId = it.track.id
            ))
        }

        suggestions.sortedByDescending { it.score }
    }

    data class TripSuggestion(
        val title: String,
        val description: String,
        val score: Float,
        val trackId: String? = null
    )

    data class DetectedSegment(
        val startIndex: Int,
        val endIndex: Int,
        val activity: ActivityType,
        val confidence: Float
    )

    // ─── DOMINANT ACTIVITY ──────────────────────────────

    private fun findDominantActivity(segments: List<DetectedSegment>): ClassificationResult {
        if (segments.isEmpty()) return ClassificationResult(ActivityType.UNKNOWN, 0f)

        val activityCounts = segments.groupBy { it.activity }
            .mapValues { (_, segs) -> segs.sumOf { it.endIndex - it.startIndex + 1 } }

        val dominant = activityCounts.maxByOrNull { it.value }
            ?: return ClassificationResult(ActivityType.UNKNOWN, 0f)

        val totalPoints = activityCounts.values.sum()
        val confidence = dominant.value.toFloat() / totalPoints

        return ClassificationResult(dominant.key, confidence)
    }

    // ─── UTILITY FUNCTIONS ──────────────────────────────

    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun calculateVariance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average()
        return values.map { (it - mean).pow(2) }.average().toFloat()
    }

    private fun formatDuration(minutes: Double): String {
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }

    private fun buildSegmentJson(segments: List<DetectedSegment>): String {
        return segments.joinToString(",", prefix = "[", postfix = "]") { seg ->
            """{"start":${seg.startIndex},"end":${seg.endIndex},"activity":"${seg.activity.name}","confidence":${seg.confidence}}"""
        }
    }

}

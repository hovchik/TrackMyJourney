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
        // Must stay consistent with ActivityConfig.defaults() in Models.kt
        private const val STATIONARY_MAX = 0.5f
        private const val WALK_MAX = 7.0f
        private const val RUN_MAX = 15.0f
        private const val CYCLE_MAX = 40.0f
        private const val DRIVE_MAX = 200.0f

        // Hysteresis margins (km/h) — prevents oscillation at boundaries
        private const val HYSTERESIS = 1.5f

        // Altitude change thresholds (meters per minute)
        private const val FLYING_ALTITUDE_RATE = 10.0f  // rapid altitude gain → flying

        // Analysis window
        private const val SEGMENT_WINDOW_SIZE = 10  // points per analysis segment
    }

    private val modelLoaded = false // TFLite classifier removed; rule-based only

    // Hysteresis: remember last detected activity to prevent oscillation at boundaries
    private var lastDetectedActivity: ActivityType = ActivityType.STATIONARY

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

        // Apply hysteresis: require speed to cross threshold + margin to change activity
        // This prevents oscillation at boundaries (e.g. 6.8-7.2 km/h flipping walk/run)
        val detected = classifySpeedWithHysteresis(speedKmh, lastDetectedActivity)
        lastDetectedActivity = detected
        return detected
    }

    private fun classifySpeedWithHysteresis(speedKmh: Float, previous: ActivityType): ActivityType {
        // To leave current activity, speed must cross threshold + hysteresis
        // To enter new activity, speed must cross threshold - hysteresis (from the other side)
        val h = HYSTERESIS
        return when (previous) {
            ActivityType.STATIONARY -> when {
                speedKmh < STATIONARY_MAX + h -> ActivityType.STATIONARY
                speedKmh < WALK_MAX -> ActivityType.WALKING
                speedKmh < RUN_MAX -> ActivityType.RUNNING
                speedKmh < CYCLE_MAX -> ActivityType.CYCLING
                speedKmh < DRIVE_MAX -> ActivityType.DRIVING
                else -> ActivityType.FLYING
            }
            ActivityType.WALKING -> when {
                speedKmh < STATIONARY_MAX -> ActivityType.STATIONARY
                speedKmh < WALK_MAX + h -> ActivityType.WALKING
                speedKmh < RUN_MAX -> ActivityType.RUNNING
                speedKmh < CYCLE_MAX -> ActivityType.CYCLING
                speedKmh < DRIVE_MAX -> ActivityType.DRIVING
                else -> ActivityType.FLYING
            }
            ActivityType.RUNNING -> when {
                speedKmh < STATIONARY_MAX -> ActivityType.STATIONARY
                speedKmh < WALK_MAX - h -> ActivityType.WALKING
                speedKmh < RUN_MAX + h -> ActivityType.RUNNING
                speedKmh < CYCLE_MAX -> ActivityType.CYCLING
                speedKmh < DRIVE_MAX -> ActivityType.DRIVING
                else -> ActivityType.FLYING
            }
            ActivityType.CYCLING -> when {
                speedKmh < STATIONARY_MAX -> ActivityType.STATIONARY
                speedKmh < WALK_MAX -> ActivityType.WALKING
                speedKmh < RUN_MAX - h -> ActivityType.RUNNING
                speedKmh < CYCLE_MAX + h -> ActivityType.CYCLING
                speedKmh < DRIVE_MAX -> ActivityType.DRIVING
                else -> ActivityType.FLYING
            }
            ActivityType.DRIVING -> when {
                speedKmh < STATIONARY_MAX -> ActivityType.STATIONARY
                speedKmh < WALK_MAX -> ActivityType.WALKING
                speedKmh < RUN_MAX -> ActivityType.RUNNING
                speedKmh < CYCLE_MAX - h -> ActivityType.CYCLING
                speedKmh < DRIVE_MAX + h -> ActivityType.DRIVING
                else -> ActivityType.FLYING
            }
            else -> when {
                speedKmh < STATIONARY_MAX -> ActivityType.STATIONARY
                speedKmh < WALK_MAX -> ActivityType.WALKING
                speedKmh < RUN_MAX -> ActivityType.RUNNING
                speedKmh < CYCLE_MAX -> ActivityType.CYCLING
                speedKmh < DRIVE_MAX -> ActivityType.DRIVING
                else -> ActivityType.FLYING
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  FULL TRACK ANALYSIS (post-recording)
    // ═══════════════════════════════════════════════════════

    suspend fun analyzeTrack(
        points: List<TrackPoint>
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

        // 4. Generate trip suggestions
        val suggestions = generateSuggestions(stats, segments)

        // 5. Build summary
        val summary = buildSummary(stats, dominantActivity, segments)

        AiAnalysis(
            trackId = trackId,
            detectedActivity = dominantActivity.activity,
            confidence = dominantActivity.confidence,
            summary = summary,
            suggestions = suggestions.joinToString("|"),
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
        // Use a high altitude rate threshold to distinguish planes from cars on hills.
        // Cars on mountain roads can easily gain 10 m/min; planes typically gain 300+ m/min.
        val altitudes = points.mapNotNull { it.altitude }
        val hasRapidAltitudeChange = if (altitudes.size >= 2) {
            val timeDiffMin = (points.last().timestamp - points.first().timestamp) / 60000.0
            if (timeDiffMin > 0) {
                val altRate = abs(altitudes.last() - altitudes.first()) / timeDiffMin
                // 50 m/min is well above what cars achieve on steep roads (~15 m/min)
                // but easily reached by aircraft during climb/descent
                altRate > 50.0
            } else false
        } else false

        val activity: ActivityType
        var confidence: Float

        when {
            // FLYING: require speed above DRIVE_MAX (200 km/h).
            // Altitude-assisted detection only applies at speeds above DRIVE_MAX,
            // boosting confidence when rapid altitude change confirms flight.
            // This prevents highway driving (e.g. 107 km/h) on hilly terrain
            // from being misclassified as flying.
            avgSpeed > DRIVE_MAX -> {
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

    // ─── TRIP SUGGESTIONS ───────────────────────────────

    private fun generateSuggestions(
        stats: TrackStats,
        segments: List<DetectedSegment>
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

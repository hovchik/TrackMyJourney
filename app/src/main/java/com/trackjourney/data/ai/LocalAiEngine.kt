package com.trackjourney.data.ai

import android.content.Context
import android.util.Log
import com.trackjourney.data.location.LocationTracker
import com.trackjourney.data.location.MotionSensorManager
import com.trackjourney.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
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
        private const val MODEL_FILE = "activity_classifier.tflite"

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

    private var interpreter: Interpreter? = null
    private var modelLoaded = false

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile()
            if (modelBuffer != null) {
                interpreter = Interpreter(modelBuffer)
                modelLoaded = true
                Log.i(TAG, "TFLite model loaded successfully")
            }
        } catch (e: Exception) {
            Log.w(TAG, "TFLite model not found, using rule-based classifier: ${e.message}")
            modelLoaded = false
        }
    }

    private fun loadModelFile(): MappedByteBuffer? {
        return try {
            val assetFd = context.assets.openFd(MODEL_FILE)
            val inputStream = FileInputStream(assetFd.fileDescriptor)
            val fileChannel = inputStream.channel
            fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                assetFd.startOffset,
                assetFd.declaredLength
            )
        } catch (e: Exception) {
            null
        }
    }

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
        // override GPS speed which is likely drift
        if (motionState != null && !motionState.isDeviceMoving && motionState.motionConfidence < 0.2f) {
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
        // GPS reports 1-7 km/h but could be drift or real walking.
        // Use step detector as tie-breaker.
        if (motionState != null && speedKmh in STATIONARY_MAX..WALK_MAX) {
            if (!motionState.stepDetected && motionState.motionConfidence < 0.35f) {
                // No steps detected and low sensor motion → likely GPS drift
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

        // Use TFLite model if available
        if (modelLoaded && interpreter != null) {
            return classifyWithModel(avgSpeed, maxSpeed, speedVariance, points)
        }

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

    private fun classifyWithModel(
        avgSpeed: Float,
        maxSpeed: Float,
        speedVariance: Float,
        points: List<TrackPoint>
    ): ClassificationResult {
        try {
            // Prepare input: [avgSpeed, maxSpeed, speedVariance, altitudeChange, pointCount]
            val altitudes = points.mapNotNull { it.altitude }
            val altitudeChange = if (altitudes.size >= 2) {
                (altitudes.last() - altitudes.first()).toFloat()
            } else 0f

            val input = ByteBuffer.allocateDirect(5 * 4).apply {
                order(ByteOrder.nativeOrder())
                putFloat(avgSpeed)
                putFloat(maxSpeed)
                putFloat(speedVariance)
                putFloat(altitudeChange)
                putFloat(points.size.toFloat())
            }

            // Output: probabilities for each activity type
            val output = Array(1) { FloatArray(ActivityType.entries.size) }
            interpreter?.run(input, output)

            val probabilities = output[0]
            val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
            val confidence = probabilities[maxIndex]

            return ClassificationResult(
                activity = ActivityType.entries[maxIndex],
                confidence = confidence
            )
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference failed, falling back to rules: ${e.message}")
            return classifyWithRules(avgSpeed, maxSpeed, speedVariance, points)
        }
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

            when {
                avgHr > 160 -> insights.add("High average heart rate — consider reducing intensity")
                avgHr in 100..140 -> insights.add("Good cardio zone for fitness improvement")
                avgHr < 60 && stats.avgSpeedKmh > 5 -> insights.add("Low heart rate during activity — excellent cardiovascular fitness")
            }
        }

        if (cadenceValues.isNotEmpty()) {
            val avgCadence = cadenceValues.average().toInt()
            insights.add("Cadence: avg $avgCadence steps/min")

            when {
                avgCadence in 170..185 -> insights.add("Optimal running cadence for efficiency")
                avgCadence < 160 && stats.avgSpeedKmh > 8 -> insights.add("Consider increasing cadence for better running form")
                avgCadence > 190 -> insights.add("High cadence — good for speed work")
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

        // Distance-based suggestions
        when {
            stats.totalDistanceKm < 1 -> {
                suggestions.add("Try extending your route — even adding 500m can improve cardiovascular benefits")
            }
            stats.totalDistanceKm in 1.0..5.0 -> {
                suggestions.add("Great short trip! Consider exploring nearby parks or trails to add variety")
            }
            stats.totalDistanceKm > 20 -> {
                suggestions.add("Impressive distance! Remember to hydrate and take breaks on long trips")
            }
        }

        // Speed variation suggestions
        val hasMultipleActivities = segments.map { it.activity }.distinct().size > 1
        if (hasMultipleActivities) {
            suggestions.add("Mixed-activity trip detected — great for cross-training and overall fitness")
        }

        // Elevation suggestions
        if (stats.totalElevationGain > 100) {
            suggestions.add("Good elevation gain (${stats.totalElevationGain.toInt()}m) — hilly routes strengthen legs and endurance")
        }

        // Time-based suggestions
        when {
            stats.durationMinutes < 15 -> {
                suggestions.add("Short session — consider aiming for 30+ minutes for optimal health benefits")
            }
            stats.durationMinutes > 120 -> {
                suggestions.add("Extended session — ensure adequate rest and nutrition after long activities")
            }
        }

        // Health-based suggestions
        val avgHr = healthData.mapNotNull { it.heartRate }.average().takeIf { !it.isNaN() }
        if (avgHr != null && avgHr > 150) {
            suggestions.add("High heart rate detected — try interval training to improve recovery")
        }

        // Best trip identification
        suggestions.add("💡 Track more trips to get personalized 'best route' recommendations based on your patterns")

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

        val sb = StringBuilder()
        sb.appendLine("📍 Trip Summary")
        sb.appendLine("Activity: $activityName ($confidence% confidence)")
        sb.appendLine("Distance: ${distance}km | Duration: $duration")
        sb.appendLine("Avg Speed: ${avgSpeed} km/h | Max: ${String.format("%.1f", stats.maxSpeedKmh)} km/h")

        if (stats.totalElevationGain > 0) {
            sb.appendLine("Elevation: ↑${stats.totalElevationGain.toInt()}m ↓${stats.totalElevationLoss.toInt()}m")
        }

        if (segments.size > 1) {
            sb.appendLine("\nActivity Segments:")
            segments.forEach { seg ->
                val name = seg.activity.name.lowercase().replaceFirstChar { it.uppercase() }
                sb.appendLine("  • $name (points ${seg.startIndex}-${seg.endIndex})")
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

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

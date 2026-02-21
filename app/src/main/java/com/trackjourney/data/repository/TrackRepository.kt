package com.trackjourney.data.repository

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.gson.GsonBuilder
import com.trackjourney.data.ai.LocalAiEngine
import com.trackjourney.data.bluetooth.WearableManager
import com.trackjourney.data.local.*
import com.trackjourney.data.location.LocationTracker
import com.trackjourney.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepository @Inject constructor(
    private val context: Context,
    private val trackDao: TrackDao,
    private val trackPointDao: TrackPointDao,
    private val healthDataDao: HealthDataDao,
    private val aiAnalysisDao: AiAnalysisDao,
    private val locationTracker: LocationTracker,
    private val wearableManager: WearableManager,
    private val aiEngine: LocalAiEngine,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "TrackRepository"
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // ─── SETTINGS ────────────────────────────────────────

    val settings: Flow<TrackingSettings> = settingsDataStore.settings

    suspend fun updateSettings(block: suspend SettingsDataStore.() -> Unit) {
        block(settingsDataStore)
    }

    // ─── TRACKS ──────────────────────────────────────────

    fun getAllTracks(): Flow<List<TrackSession>> = trackDao.getAllTracks()

    fun getAllTracksWithPoints(): Flow<List<TrackWithPoints>> = trackDao.getAllTracksWithPoints()

    fun observeActiveTrack(): Flow<TrackSession?> = trackDao.observeActiveTrack()

    suspend fun getActiveTrack(): TrackSession? = trackDao.getActiveTrack()

    suspend fun getTrackById(id: String): TrackSession? = trackDao.getTrackById(id)

    suspend fun getTrackWithPoints(id: String): TrackWithPoints? = trackDao.getTrackWithPoints(id)

    fun getTracksByActivity(type: ActivityType): Flow<List<TrackSession>> =
        trackDao.getTracksByActivity(type)

    suspend fun getStats(): TrackingStats {
        return TrackingStats(
            totalTracks = trackDao.getTrackCount(),
            totalDistanceKm = (trackDao.getTotalDistance() ?: 0.0) / 1000.0,
            averageSpeedKmh = trackDao.getAverageSpeed() ?: 0.0
        )
    }

    // ─── TRACKING LIFECYCLE ─────────────────────────────

    suspend fun startNewTrack(name: String = ""): TrackSession {
        // End any active track first
        getActiveTrack()?.let { endTrack(it.id) }

        val track = TrackSession(
            name = name.ifEmpty {
                val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                "Trip ${sdf.format(Date())}"
            },
            startTime = System.currentTimeMillis(),
            isActive = true
        )
        trackDao.insert(track)
        return track
    }

    suspend fun addTrackPoint(
        trackId: String,
        location: Location,
        healthReading: com.trackjourney.data.bluetooth.WearableReading? = null
    ): TrackPoint {
        val lastPoint = trackPointDao.getLastPoint(trackId)
        val speedKmh = LocationTracker.msToKmh(location.speed)

        // AI-based real-time activity detection
        val activity = aiEngine.detectActivity(
            speedKmh = speedKmh,
            altitude = if (location.hasAltitude()) location.altitude else null,
            previousAltitude = lastPoint?.altitude
        )

        val point = TrackPoint(
            trackId = trackId,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speedMs = location.speed,
            speedKmh = speedKmh,
            bearing = if (location.hasBearing()) location.bearing else null,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            timestamp = System.currentTimeMillis(),
            heartRate = healthReading?.heartRate,
            spO2 = healthReading?.spO2,
            activityType = activity
        )

        trackPointDao.insert(point)

        // Store separate health data record if available
        if (healthReading != null && (healthReading.heartRate != null || healthReading.spO2 != null)) {
            healthDataDao.insert(HealthData(
                trackId = trackId,
                heartRate = healthReading.heartRate,
                spO2 = healthReading.spO2,
                deviceName = healthReading.deviceName,
                deviceType = healthReading.deviceType
            ))
        }

        // Update track stats
        updateTrackStats(trackId)

        return point
    }

    private suspend fun updateTrackStats(trackId: String) {
        val track = trackDao.getTrackById(trackId) ?: return
        val points = trackPointDao.getPointsForTrackSync(trackId)

        if (points.size < 2) return

        var totalDistance = 0.0
        for (i in 1 until points.size) {
            totalDistance += LocationTracker.distanceBetween(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
        }

        val avgSpeed = trackPointDao.getAverageSpeed(trackId) ?: 0f
        val maxSpeed = trackPointDao.getMaxSpeed(trackId) ?: 0f
        val avgHr = healthDataDao.getAverageHeartRate(trackId)
        val avgSpO2 = healthDataDao.getAverageSpO2(trackId)

        // Determine dominant activity from latest points
        val recentActivities = points.takeLast(20).map { it.activityType }
        val dominant = recentActivities
            .groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key ?: ActivityType.UNKNOWN

        trackDao.update(track.copy(
            distanceMeters = totalDistance,
            avgSpeedKmh = avgSpeed.toDouble(),
            maxSpeedKmh = maxSpeed.toDouble(),
            activityType = dominant,
            avgHeartRate = avgHr,
            avgSpO2 = avgSpO2
        ))
    }

    suspend fun endTrack(trackId: String): TrackSession? {
        val track = trackDao.getTrackById(trackId) ?: return null
        val updatedTrack = track.copy(
            endTime = System.currentTimeMillis(),
            isActive = false
        )
        trackDao.update(updatedTrack)

        // Trigger AI analysis
        analyzeTrack(trackId)

        return updatedTrack
    }

    suspend fun deleteTrack(trackId: String) {
        trackDao.getTrackById(trackId)?.let { trackDao.delete(it) }
    }

    suspend fun renameTrack(trackId: String, name: String) {
        trackDao.getTrackById(trackId)?.let {
            trackDao.update(it.copy(name = name))
        }
    }

    // ─── TRACK POINTS ───────────────────────────────────

    fun getPointsForTrack(trackId: String): Flow<List<TrackPoint>> =
        trackPointDao.getPointsForTrack(trackId)

    // ─── AI ANALYSIS ────────────────────────────────────

    suspend fun analyzeTrack(trackId: String): AiAnalysis? = withContext(Dispatchers.Default) {
        try {
            val points = trackPointDao.getPointsForTrackSync(trackId)
            val healthData = healthDataDao.getHealthDataForTrackSync(trackId)

            if (points.isEmpty()) return@withContext null

            val analysis = aiEngine.analyzeTrack(points, healthData)
            val finalAnalysis = analysis.copy(trackId = trackId)
            aiAnalysisDao.insert(finalAnalysis)

            // Update track with AI summary
            trackDao.getTrackById(trackId)?.let { track ->
                trackDao.update(track.copy(
                    aiSummary = finalAnalysis.summary,
                    activityType = finalAnalysis.detectedActivity
                ))
            }

            finalAnalysis
        } catch (e: Exception) {
            Log.e(TAG, "AI analysis failed: ${e.message}")
            null
        }
    }

    fun getAnalysisForTrack(trackId: String): Flow<AiAnalysis?> =
        aiAnalysisDao.observeLatestAnalysis(trackId)

    suspend fun suggestBestTrips(): List<LocalAiEngine.TripSuggestion> {
        val tracks = trackDao.getAllTracksWithPoints().first()
        return aiEngine.suggestBestTrips(tracks)
    }

    // ─── JSON EXPORT ────────────────────────────────────

    suspend fun exportTrackToJson(trackId: String): File? = withContext(Dispatchers.IO) {
        try {
            val trackWithPoints = trackDao.getTrackWithPoints(trackId) ?: return@withContext null
            val analysis = aiAnalysisDao.getLatestAnalysis(trackId)
            val track = trackWithPoints.track
            val points = trackWithPoints.points
            val health = trackWithPoints.healthData

            val export = TrackExport(
                session = TrackSessionExport(
                    id = track.id,
                    name = track.name,
                    startTime = track.startTime,
                    endTime = track.endTime,
                    distanceMeters = track.distanceMeters,
                    avgSpeedKmh = track.avgSpeedKmh,
                    maxSpeedKmh = track.maxSpeedKmh,
                    activityType = track.activityType.name,
                    avgHeartRate = track.avgHeartRate,
                    avgSpO2 = track.avgSpO2
                ),
                points = points.map { pt ->
                    TrackPointExport(
                        latitude = pt.latitude,
                        longitude = pt.longitude,
                        altitude = pt.altitude,
                        speedKmh = pt.speedKmh,
                        bearing = pt.bearing,
                        accuracy = pt.accuracy,
                        timestamp = pt.timestamp,
                        heartRate = pt.heartRate,
                        spO2 = pt.spO2,
                        activityType = pt.activityType.name
                    )
                },
                healthData = health.map { hd ->
                    HealthDataExport(
                        timestamp = hd.timestamp,
                        heartRate = hd.heartRate,
                        spO2 = hd.spO2,
                        deviceName = hd.deviceName,
                        deviceType = hd.deviceType.name
                    )
                },
                aiAnalysis = analysis?.let { a ->
                    AiAnalysisExport(
                        detectedActivity = a.detectedActivity.name,
                        confidence = a.confidence,
                        summary = a.summary,
                        suggestions = a.suggestions.split("|").filter { it.isNotBlank() },
                        healthInsights = a.healthInsights,
                        segmentActivities = null // Could parse from JSON string if needed
                    )
                }
            )

            val sdf = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
            val fileName = "track_${sdf.format(Date(track.startTime))}.json"
            val dir = File(context.getExternalFilesDir(null), "tracks")
            dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(gson.toJson(export))

            Log.i(TAG, "Track exported to: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            null
        }
    }

    // ─── WEARABLE DELEGATION ────────────────────────────

    val wearableConnectionState = wearableManager.connectionState
    val wearableReading = wearableManager.latestReading

    fun scanForWearables() = wearableManager.scanForDevices()

    fun connectWearable(device: com.trackjourney.data.bluetooth.WearableDevice) =
        wearableManager.connectToDevice(device)

    fun disconnectWearable() = wearableManager.disconnect()
}

data class TrackingStats(
    val totalTracks: Int,
    val totalDistanceKm: Double,
    val averageSpeedKmh: Double
)

package com.trackjourney.data.repository

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.util.Log
import com.google.gson.GsonBuilder
import com.trackjourney.data.ai.LocalAiEngine
import com.trackjourney.data.local.*
import com.trackjourney.data.location.GpsSatelliteTracker
import com.trackjourney.data.location.LocationTracker
import com.trackjourney.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
class TrackRepository(
    private val context: Context,
    private val trackDao: TrackDao,
    private val trackPointDao: TrackPointDao,
    private val healthDataDao: HealthDataDao,
    private val aiAnalysisDao: AiAnalysisDao,
    private val locationTracker: LocationTracker,
    private val aiEngine: LocalAiEngine,
    private val settingsDataStore: SettingsDataStore,
    private val gpsSatelliteTracker: GpsSatelliteTracker
) {
    companion object {
        private const val TAG = "TrackRepository"
        const val MIN_SATELLITES_FOR_ACCURATE_FIX = 4
        const val MAX_ACCURACY_METERS = 50f // reject points worse than this
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val geocoder = Geocoder(context, Locale.getDefault())

    // ─── GEOCODING ─────────────────────────────────────────

    private fun resolveplaceName(latitude: Double, longitude: Double): String? {
        return try {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            addresses?.firstOrNull()?.let { addr ->
                addr.locality
                    ?: addr.subAdminArea
                    ?: addr.adminArea
                    ?: addr.getAddressLine(0)?.split(",")?.firstOrNull()?.trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding failed: ${e.message}")
            null
        }
    }

    // ─── SETTINGS ────────────────────────────────────────

    val settings: Flow<TrackingSettings> = settingsDataStore.settings

    suspend fun updateSettings(block: suspend SettingsDataStore.() -> Unit) {
        block(settingsDataStore)
    }

    // ─── GPS SATELLITE INFO ────────────────────────────────

    val satelliteInfo = gpsSatelliteTracker.satelliteInfo

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

    suspend fun getStatsSince(since: Long): PeriodStats {
        return PeriodStats(
            trackCount = trackDao.getTrackCountSince(since),
            totalDistanceKm = (trackDao.getTotalDistanceSince(since) ?: 0.0) / 1000.0,
            totalDurationMs = trackDao.getTotalDurationSince(since) ?: 0L,
            averageSpeedKmh = trackDao.getAverageSpeedSince(since) ?: 0.0,
            maxSpeedKmh = trackDao.getMaxSpeedSince(since) ?: 0.0
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
    ): TrackPoint? {
        val lastPoint = trackPointDao.getLastPoint(trackId)
        val speedKmh = LocationTracker.msToKmh(location.speed)
        val satInfo = gpsSatelliteTracker.satelliteInfo.value
        val accuracyMeters = if (location.hasAccuracy()) location.accuracy else null

        // Determine accuracy based on satellites and GPS accuracy
        val isAccurate = satInfo.usedInFix >= MIN_SATELLITES_FOR_ACCURATE_FIX
                && (accuracyMeters == null || accuracyMeters <= MAX_ACCURACY_METERS)

        // Log inaccurate records
        if (!isAccurate) {
            Log.w(TAG, "INACCURATE GPS: sats=${satInfo.usedInFix}/${satInfo.totalVisible}, " +
                    "accuracy=${accuracyMeters}m, lat=${location.latitude}, lon=${location.longitude}")
        }

        // Skip entirely if accuracy is extremely poor (>100m) — saves storage
        if (accuracyMeters != null && accuracyMeters > 100f) {
            Log.w(TAG, "SKIPPED point: accuracy ${accuracyMeters}m exceeds 100m threshold")
            return null
        }

        // AI-based real-time activity detection
        val activity = aiEngine.detectActivity(
            speedKmh = speedKmh,
            altitude = if (location.hasAltitude()) location.altitude else null,
            previousAltitude = lastPoint?.altitude
        )

        // Resolve place name for the first point of a track
        val isFirstPoint = lastPoint == null
        val placeName = if (isFirstPoint) {
            withContext(Dispatchers.IO) { resolveplaceName(location.latitude, location.longitude) }
        } else null

        val point = TrackPoint(
            trackId = trackId,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speedMs = location.speed,
            speedKmh = speedKmh,
            bearing = if (location.hasBearing()) location.bearing else null,
            accuracy = accuracyMeters,
            timestamp = System.currentTimeMillis(),
            heartRate = healthReading?.heartRate,
            cadence = healthReading?.cadence,
            activityType = activity,
            placeName = placeName,
            satellitesUsed = satInfo.usedInFix,
            isAccurate = isAccurate
        )

        trackPointDao.insert(point)

        // Set start place name on the track session
        if (isFirstPoint && placeName != null) {
            trackDao.getTrackById(trackId)?.let { track ->
                if (track.startPlaceName == null) {
                    trackDao.update(track.copy(startPlaceName = placeName))
                }
            }
        }

        // Store separate health data record if available
        if (healthReading != null && healthReading.heartRate != null) {
            healthDataDao.insert(HealthData(
                trackId = trackId,
                heartRate = healthReading.heartRate,
                batteryLevel = healthReading.batteryLevel,
                cadence = healthReading.cadence,
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
            avgHeartRate = avgHr
        ))
    }

    suspend fun endTrack(trackId: String): TrackSession? {
        val track = trackDao.getTrackById(trackId) ?: return null

        // Resolve end place name from the last point
        val lastPoint = trackPointDao.getLastPoint(trackId)
        val endPlaceName = lastPoint?.let {
            withContext(Dispatchers.IO) { resolveplaceName(it.latitude, it.longitude) }
        }

        val updatedTrack = track.copy(
            endTime = System.currentTimeMillis(),
            isActive = false,
            endPlaceName = endPlaceName
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

    // ─── EXPORT ───────────────────────────────────────────

    suspend fun exportTrack(trackId: String, format: ExportFormat): File? {
        return when (format) {
            ExportFormat.JSON -> exportTrackToJson(trackId)
            ExportFormat.GPX  -> exportTrackToGpx(trackId)
            ExportFormat.CSV  -> exportTrackToCsv(trackId)
        }
    }

    private suspend fun exportTrackToJson(trackId: String): File? = withContext(Dispatchers.IO) {
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
                    startPlaceName = track.startPlaceName,
                    endPlaceName = track.endPlaceName
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
                        cadence = pt.cadence,
                        activityType = pt.activityType.name,
                        placeName = pt.placeName,
                        satellitesUsed = pt.satellitesUsed,
                        isAccurate = pt.isAccurate
                    )
                },
                healthData = health.map { hd ->
                    HealthDataExport(
                        timestamp = hd.timestamp,
                        heartRate = hd.heartRate,
                        batteryLevel = hd.batteryLevel,
                        cadence = hd.cadence,
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
                        segmentActivities = null
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
            Log.e(TAG, "JSON export failed: ${e.message}")
            null
        }
    }

    private suspend fun exportTrackToGpx(trackId: String): File? = withContext(Dispatchers.IO) {
        try {
            val trackWithPoints = trackDao.getTrackWithPoints(trackId) ?: return@withContext null
            val track = trackWithPoints.track
            val points = trackWithPoints.points
            val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            val gpx = buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine("""<gpx version="1.1" creator="TrackMyJourney"
  xmlns="http://www.topografix.com/GPX/1/1"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""")
                appendLine("  <metadata>")
                appendLine("    <name>${escapeXml(track.name)}</name>")
                appendLine("    <time>${isoFmt.format(Date(track.startTime))}</time>")
                appendLine("  </metadata>")
                appendLine("  <trk>")
                appendLine("    <name>${escapeXml(track.name)}</name>")
                appendLine("    <type>${track.activityType.name}</type>")
                appendLine("    <trkseg>")
                for (pt in points) {
                    append("      <trkpt lat=\"${pt.latitude}\" lon=\"${pt.longitude}\">")
                    pt.altitude?.let { append("<ele>$it</ele>") }
                    append("<time>${isoFmt.format(Date(pt.timestamp))}</time>")
                    pt.heartRate?.let { append("<extensions><hr>$it</hr></extensions>") }
                    appendLine("</trkpt>")
                }
                appendLine("    </trkseg>")
                appendLine("  </trk>")
                appendLine("</gpx>")
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
            val fileName = "track_${sdf.format(Date(track.startTime))}.gpx"
            val dir = File(context.getExternalFilesDir(null), "tracks")
            dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(gpx)

            Log.i(TAG, "GPX exported to: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "GPX export failed: ${e.message}")
            null
        }
    }

    private suspend fun exportTrackToCsv(trackId: String): File? = withContext(Dispatchers.IO) {
        try {
            val trackWithPoints = trackDao.getTrackWithPoints(trackId) ?: return@withContext null
            val track = trackWithPoints.track
            val points = trackWithPoints.points
            val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            val csv = buildString {
                appendLine("timestamp,latitude,longitude,altitude,speed_kmh,bearing,accuracy,heart_rate,cadence,activity,satellites,accurate")
                for (pt in points) {
                    appendLine(buildString {
                        append(isoFmt.format(Date(pt.timestamp))).append(',')
                        append(pt.latitude).append(',')
                        append(pt.longitude).append(',')
                        append(pt.altitude ?: "").append(',')
                        append(pt.speedKmh).append(',')
                        append(pt.bearing ?: "").append(',')
                        append(pt.accuracy ?: "").append(',')
                        append(pt.heartRate ?: "").append(',')
                        append(pt.cadence ?: "").append(',')
                        append(pt.activityType.name).append(',')
                        append(pt.satellitesUsed ?: "").append(',')
                        append(pt.isAccurate)
                    })
                }
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
            val fileName = "track_${sdf.format(Date(track.startTime))}.csv"
            val dir = File(context.getExternalFilesDir(null), "tracks")
            dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(csv)

            Log.i(TAG, "CSV exported to: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "CSV export failed: ${e.message}")
            null
        }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    // ─── IMPORT ───────────────────────────────────────────

    data class ImportResult(
        val trackId: String,
        val trackName: String,
        val pointCount: Int
    )

    suspend fun importTrack(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val fileName = getFileName(uri)
        val content = readUri(uri)

        when {
            fileName.endsWith(".json", ignoreCase = true) -> importFromJson(content)
            fileName.endsWith(".gpx", ignoreCase = true)  -> importFromGpx(content)
            fileName.endsWith(".csv", ignoreCase = true)   -> importFromCsv(content)
            else -> {
                // Try to detect format from content
                val trimmed = content.trimStart()
                when {
                    trimmed.startsWith("{") -> importFromJson(content)
                    trimmed.startsWith("<?xml") || trimmed.startsWith("<gpx") -> importFromGpx(content)
                    trimmed.contains(",latitude,") || trimmed.contains(",longitude,") -> importFromCsv(content)
                    else -> throw IllegalArgumentException("Unsupported file format")
                }
            }
        }
    }

    private fun readUri(uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        } ?: throw IllegalArgumentException("Could not read file")
    }

    private fun getFileName(uri: Uri): String {
        // Try to get the display name from content resolver
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }

    private suspend fun importFromJson(content: String): ImportResult {
        val export = gson.fromJson(content, TrackExport::class.java)
            ?: throw IllegalArgumentException("Invalid JSON track data")

        val session = export.session
        val trackId = UUID.randomUUID().toString()

        val track = TrackSession(
            id = trackId,
            name = session.name.ifEmpty { "Imported Track" },
            startTime = session.startTime,
            endTime = session.endTime,
            distanceMeters = session.distanceMeters,
            avgSpeedKmh = session.avgSpeedKmh,
            maxSpeedKmh = session.maxSpeedKmh,
            activityType = parseActivityType(session.activityType),
            avgHeartRate = session.avgHeartRate,
            startPlaceName = session.startPlaceName,
            endPlaceName = session.endPlaceName,
            isActive = false
        )
        trackDao.insert(track)

        val points = export.points.map { pt ->
            TrackPoint(
                trackId = trackId,
                latitude = pt.latitude,
                longitude = pt.longitude,
                altitude = pt.altitude,
                speedKmh = pt.speedKmh,
                speedMs = pt.speedKmh / 3.6f,
                bearing = pt.bearing,
                accuracy = pt.accuracy,
                timestamp = pt.timestamp,
                heartRate = pt.heartRate,
                cadence = pt.cadence,
                activityType = parseActivityType(pt.activityType),
                placeName = pt.placeName,
                satellitesUsed = pt.satellitesUsed,
                isAccurate = pt.isAccurate
            )
        }
        trackPointDao.insertAll(points)

        // Import health data
        export.healthData.forEach { hd ->
            healthDataDao.insert(HealthData(
                trackId = trackId,
                timestamp = hd.timestamp,
                heartRate = hd.heartRate,
                batteryLevel = hd.batteryLevel,
                cadence = hd.cadence,
                deviceName = hd.deviceName,
                deviceType = parseWearableType(hd.deviceType)
            ))
        }

        // Import AI analysis
        export.aiAnalysis?.let { ai ->
            aiAnalysisDao.insert(AiAnalysis(
                trackId = trackId,
                detectedActivity = parseActivityType(ai.detectedActivity),
                confidence = ai.confidence,
                summary = ai.summary,
                suggestions = ai.suggestions.joinToString("|"),
                healthInsights = ai.healthInsights
            ))
            // Set AI summary on track
            trackDao.update(track.copy(aiSummary = ai.summary))
        }

        Log.i(TAG, "JSON import: ${points.size} points for track '${track.name}'")
        return ImportResult(trackId, track.name, points.size)
    }

    private suspend fun importFromGpx(content: String): ImportResult {
        val trackId = UUID.randomUUID().toString()
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // Simple XML parsing — extract track name, type, and trackpoints
        val nameMatch = Regex("<trk>.*?<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(content)
        val trackName = nameMatch?.groupValues?.get(1)?.let { unescapeXml(it) } ?: "Imported GPX"

        val typeMatch = Regex("<type>(.*?)</type>").find(content)
        val activityType = typeMatch?.groupValues?.get(1)?.let { parseActivityType(it) } ?: ActivityType.UNKNOWN

        // Parse trackpoints
        val trkptRegex = Regex("""<trkpt\s+lat="([^"]+)"\s+lon="([^"]+)">(.*?)</trkpt>""", RegexOption.DOT_MATCHES_ALL)
        val eleRegex = Regex("<ele>(.*?)</ele>")
        val timeRegex = Regex("<time>(.*?)</time>")
        val hrRegex = Regex("<hr>(.*?)</hr>")

        val points = mutableListOf<TrackPoint>()
        for (match in trkptRegex.findAll(content)) {
            val lat = match.groupValues[1].toDoubleOrNull() ?: continue
            val lon = match.groupValues[2].toDoubleOrNull() ?: continue
            val inner = match.groupValues[3]

            val altitude = eleRegex.find(inner)?.groupValues?.get(1)?.toDoubleOrNull()
            val timestamp = timeRegex.find(inner)?.groupValues?.get(1)?.let {
                try { isoFmt.parse(it)?.time } catch (e: Exception) { null }
            } ?: System.currentTimeMillis()
            val heartRate = hrRegex.find(inner)?.groupValues?.get(1)?.toIntOrNull()

            points.add(TrackPoint(
                trackId = trackId,
                latitude = lat,
                longitude = lon,
                altitude = altitude,
                timestamp = timestamp,
                heartRate = heartRate,
                activityType = activityType,
                isAccurate = true
            ))
        }

        if (points.isEmpty()) throw IllegalArgumentException("No trackpoints found in GPX file")

        // Calculate speed between consecutive points
        val enrichedPoints = points.mapIndexed { i, pt ->
            if (i == 0) return@mapIndexed pt
            val prev = points[i - 1]
            val distM = LocationTracker.distanceBetween(prev.latitude, prev.longitude, pt.latitude, pt.longitude)
            val timeDiffS = (pt.timestamp - prev.timestamp) / 1000.0
            val speedMs = if (timeDiffS > 0) (distM / timeDiffS).toFloat() else 0f
            pt.copy(speedMs = speedMs, speedKmh = speedMs * 3.6f)
        }

        // Calculate track stats
        var totalDistance = 0.0
        for (i in 1 until enrichedPoints.size) {
            totalDistance += LocationTracker.distanceBetween(
                enrichedPoints[i - 1].latitude, enrichedPoints[i - 1].longitude,
                enrichedPoints[i].latitude, enrichedPoints[i].longitude
            )
        }
        val avgSpeed = enrichedPoints.filter { it.speedKmh > 0 }.map { it.speedKmh }.average().let {
            if (it.isNaN()) 0.0 else it
        }
        val maxSpeed = enrichedPoints.maxOf { it.speedKmh }.toDouble()

        val track = TrackSession(
            id = trackId,
            name = trackName,
            startTime = enrichedPoints.first().timestamp,
            endTime = enrichedPoints.last().timestamp,
            distanceMeters = totalDistance,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed,
            activityType = activityType,
            isActive = false
        )
        trackDao.insert(track)
        trackPointDao.insertAll(enrichedPoints)

        Log.i(TAG, "GPX import: ${enrichedPoints.size} points for track '$trackName'")
        return ImportResult(trackId, trackName, enrichedPoints.size)
    }

    private suspend fun importFromCsv(content: String): ImportResult {
        val trackId = UUID.randomUUID().toString()
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.size < 2) throw IllegalArgumentException("CSV file is empty or has no data rows")

        val header = lines.first().split(",").map { it.trim().lowercase() }
        val latIdx = header.indexOfFirst { it == "latitude" || it == "lat" }
        val lonIdx = header.indexOfFirst { it == "longitude" || it == "lon" || it == "lng" }
        if (latIdx < 0 || lonIdx < 0) throw IllegalArgumentException("CSV must have latitude and longitude columns")

        val tsIdx = header.indexOfFirst { it == "timestamp" || it == "time" || it == "datetime" }
        val altIdx = header.indexOfFirst { it == "altitude" || it == "ele" || it == "elevation" }
        val speedIdx = header.indexOfFirst { it == "speed_kmh" || it == "speed" }
        val hrIdx = header.indexOfFirst { it == "heart_rate" || it == "hr" || it == "heartrate" }
        val cadIdx = header.indexOfFirst { it == "cadence" }
        val actIdx = header.indexOfFirst { it == "activity" || it == "activity_type" }
        val accIdx = header.indexOfFirst { it == "accuracy" }
        val satIdx = header.indexOfFirst { it == "satellites" || it == "satellites_used" }

        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val points = mutableListOf<TrackPoint>()
        for (line in lines.drop(1)) {
            val cols = line.split(",").map { it.trim() }
            val lat = cols.getOrNull(latIdx)?.toDoubleOrNull() ?: continue
            val lon = cols.getOrNull(lonIdx)?.toDoubleOrNull() ?: continue

            val timestamp = if (tsIdx >= 0) {
                val tsVal = cols.getOrNull(tsIdx) ?: ""
                tsVal.toLongOrNull() ?: try { isoFmt.parse(tsVal)?.time } catch (e: Exception) { null }
                ?: System.currentTimeMillis()
            } else System.currentTimeMillis()

            val altitude = if (altIdx >= 0) cols.getOrNull(altIdx)?.toDoubleOrNull() else null
            val speedKmh = if (speedIdx >= 0) cols.getOrNull(speedIdx)?.toFloatOrNull() ?: 0f else 0f
            val heartRate = if (hrIdx >= 0) cols.getOrNull(hrIdx)?.toIntOrNull() else null
            val cadence = if (cadIdx >= 0) cols.getOrNull(cadIdx)?.toIntOrNull() else null
            val activity = if (actIdx >= 0) cols.getOrNull(actIdx)?.let { parseActivityType(it) } ?: ActivityType.UNKNOWN else ActivityType.UNKNOWN
            val accuracy = if (accIdx >= 0) cols.getOrNull(accIdx)?.toFloatOrNull() else null
            val satellites = if (satIdx >= 0) cols.getOrNull(satIdx)?.toIntOrNull() else null

            points.add(TrackPoint(
                trackId = trackId,
                latitude = lat,
                longitude = lon,
                altitude = altitude,
                speedKmh = speedKmh,
                speedMs = speedKmh / 3.6f,
                timestamp = timestamp,
                heartRate = heartRate,
                cadence = cadence,
                activityType = activity,
                accuracy = accuracy,
                satellitesUsed = satellites,
                isAccurate = true
            ))
        }

        if (points.isEmpty()) throw IllegalArgumentException("No valid data rows found in CSV")

        // Calculate stats
        var totalDistance = 0.0
        for (i in 1 until points.size) {
            totalDistance += LocationTracker.distanceBetween(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
        }
        val avgSpeed = points.filter { it.speedKmh > 0 }.map { it.speedKmh }.average().let {
            if (it.isNaN()) 0.0 else it
        }
        val maxSpeed = points.maxOf { it.speedKmh }.toDouble()
        val dominant = points.map { it.activityType }
            .groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key ?: ActivityType.UNKNOWN

        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        val track = TrackSession(
            id = trackId,
            name = "Imported ${sdf.format(Date(points.first().timestamp))}",
            startTime = points.first().timestamp,
            endTime = points.last().timestamp,
            distanceMeters = totalDistance,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed,
            activityType = dominant,
            isActive = false
        )
        trackDao.insert(track)
        trackPointDao.insertAll(points)

        Log.i(TAG, "CSV import: ${points.size} points")
        return ImportResult(trackId, track.name, points.size)
    }

    private fun parseActivityType(value: String): ActivityType =
        try { ActivityType.valueOf(value.uppercase()) } catch (e: Exception) { ActivityType.UNKNOWN }

    private fun parseWearableType(value: String): WearableType =
        try { WearableType.valueOf(value.uppercase()) } catch (e: Exception) { WearableType.UNKNOWN }

    private fun unescapeXml(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    // ─── LOCATION ─────────────────────────────────────────

    suspend fun getCurrentLocation(): android.location.Location? {
        return locationTracker.getLastKnownLocation()
    }
}

data class TrackingStats(
    val totalTracks: Int,
    val totalDistanceKm: Double,
    val averageSpeedKmh: Double
)

data class PeriodStats(
    val trackCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val totalDurationMs: Long = 0L,
    val averageSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0
)

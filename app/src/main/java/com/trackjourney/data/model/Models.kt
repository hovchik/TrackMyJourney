package com.trackjourney.data.model

import androidx.room.*
import com.google.gson.annotations.SerializedName
import java.util.UUID

// ─────────────────────────────────────────────────────────
//  TRACK SESSION — One recorded journey
// ─────────────────────────────────────────────────────────

@Entity(tableName = "tracks")
data class TrackSession(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "start_time")
    val startTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "end_time")
    val endTime: Long? = null,

    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double = 0.0,

    @ColumnInfo(name = "avg_speed_kmh")
    val avgSpeedKmh: Double = 0.0,

    @ColumnInfo(name = "max_speed_kmh")
    val maxSpeedKmh: Double = 0.0,

    @ColumnInfo(name = "activity_type")
    val activityType: ActivityType = ActivityType.UNKNOWN,

    @ColumnInfo(name = "avg_heart_rate")
    val avgHeartRate: Int? = null,

    @ColumnInfo(name = "ai_summary")
    val aiSummary: String? = null,

    @ColumnInfo(name = "start_place_name")
    val startPlaceName: String? = null,

    @ColumnInfo(name = "end_place_name")
    val endPlaceName: String? = null,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true
)

// ─────────────────────────────────────────────────────────
//  TRACK POINT — Individual GPS fix + sensor data
// ─────────────────────────────────────────────────────────

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(
        entity = TrackSession::class,
        parentColumns = ["id"],
        childColumns = ["track_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["track_id"])]
)
data class TrackPoint(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,

    @ColumnInfo(name = "altitude")
    val altitude: Double? = null,

    @ColumnInfo(name = "speed_ms")
    val speedMs: Float = 0f,             // meters/second from GPS

    @ColumnInfo(name = "speed_kmh")
    val speedKmh: Float = 0f,            // computed km/h

    @ColumnInfo(name = "bearing")
    val bearing: Float? = null,

    @ColumnInfo(name = "accuracy")
    val accuracy: Float? = null,          // GPS accuracy in meters

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "heart_rate")
    val heartRate: Int? = null,           // BPM from wearable

    @ColumnInfo(name = "cadence")
    val cadence: Int? = null,            // steps/min or cycling rpm

    @ColumnInfo(name = "activity_type")
    val activityType: ActivityType = ActivityType.UNKNOWN,

    @ColumnInfo(name = "place_name")
    val placeName: String? = null
)

// ─────────────────────────────────────────────────────────
//  HEALTH DATA SNAPSHOT — from smartwatch
// ─────────────────────────────────────────────────────────

@Entity(
    tableName = "health_data",
    foreignKeys = [ForeignKey(
        entity = TrackSession::class,
        parentColumns = ["id"],
        childColumns = ["track_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["track_id"])]
)
data class HealthData(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "heart_rate")
    val heartRate: Int? = null,

    @ColumnInfo(name = "battery_level")
    val batteryLevel: Int? = null,

    @ColumnInfo(name = "cadence")
    val cadence: Int? = null,

    @ColumnInfo(name = "device_name")
    val deviceName: String? = null,

    @ColumnInfo(name = "device_type")
    val deviceType: WearableType = WearableType.UNKNOWN
)

// ─────────────────────────────────────────────────────────
//  AI ANALYSIS RESULT
// ─────────────────────────────────────────────────────────

@Entity(
    tableName = "ai_analyses",
    foreignKeys = [ForeignKey(
        entity = TrackSession::class,
        parentColumns = ["id"],
        childColumns = ["track_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["track_id"])]
)
data class AiAnalysis(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "detected_activity")
    val detectedActivity: ActivityType = ActivityType.UNKNOWN,

    @ColumnInfo(name = "confidence")
    val confidence: Float = 0f,

    @ColumnInfo(name = "summary")
    val summary: String = "",

    @ColumnInfo(name = "suggestions")
    val suggestions: String = "",             // JSON array of suggestions

    @ColumnInfo(name = "health_insights")
    val healthInsights: String? = null,

    @ColumnInfo(name = "segment_activities")
    val segmentActivities: String? = null     // JSON: [{start, end, activity}]
)

// ─────────────────────────────────────────────────────────
//  ENUMS
// ─────────────────────────────────────────────────────────

enum class ActivityType {
    WALKING,
    RUNNING,
    CYCLING,
    DRIVING,
    FLYING,
    STATIONARY,
    UNKNOWN;

    companion object {
        fun fromSpeed(speedKmh: Float): ActivityType = when {
            speedKmh < 0.5f    -> STATIONARY
            speedKmh < 7f      -> WALKING
            speedKmh < 15f     -> RUNNING
            speedKmh < 35f     -> CYCLING
            speedKmh < 200f    -> DRIVING
            else                -> FLYING
        }
    }
}

enum class WearableType {
    GARMIN,
    SAMSUNG,
    GENERIC_BLE,
    UNKNOWN
}

// ─────────────────────────────────────────────────────────
//  JSON EXPORT MODELS
// ─────────────────────────────────────────────────────────

data class TrackExport(
    @SerializedName("session")
    val session: TrackSessionExport,
    @SerializedName("points")
    val points: List<TrackPointExport>,
    @SerializedName("health_data")
    val healthData: List<HealthDataExport>,
    @SerializedName("ai_analysis")
    val aiAnalysis: AiAnalysisExport?
)

data class TrackSessionExport(
    val id: String,
    val name: String,
    @SerializedName("start_time") val startTime: Long,
    @SerializedName("end_time") val endTime: Long?,
    @SerializedName("distance_meters") val distanceMeters: Double,
    @SerializedName("avg_speed_kmh") val avgSpeedKmh: Double,
    @SerializedName("max_speed_kmh") val maxSpeedKmh: Double,
    @SerializedName("activity_type") val activityType: String,
    @SerializedName("avg_heart_rate") val avgHeartRate: Int?,
    @SerializedName("start_place_name") val startPlaceName: String?,
    @SerializedName("end_place_name") val endPlaceName: String?
)

data class TrackPointExport(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    @SerializedName("speed_kmh") val speedKmh: Float,
    val bearing: Float?,
    val accuracy: Float?,
    val timestamp: Long,
    @SerializedName("heart_rate") val heartRate: Int?,
    val cadence: Int?,
    @SerializedName("activity_type") val activityType: String,
    @SerializedName("place_name") val placeName: String?
)

data class HealthDataExport(
    val timestamp: Long,
    @SerializedName("heart_rate") val heartRate: Int?,
    @SerializedName("battery_level") val batteryLevel: Int?,
    val cadence: Int?,
    @SerializedName("device_name") val deviceName: String?,
    @SerializedName("device_type") val deviceType: String
)

data class AiAnalysisExport(
    @SerializedName("detected_activity") val detectedActivity: String,
    val confidence: Float,
    val summary: String,
    val suggestions: List<String>,
    @SerializedName("health_insights") val healthInsights: String?,
    @SerializedName("segment_activities") val segmentActivities: List<ActivitySegment>?
)

data class ActivitySegment(
    @SerializedName("start_index") val startIndex: Int,
    @SerializedName("end_index") val endIndex: Int,
    @SerializedName("activity_type") val activityType: String,
    val confidence: Float
)

// ─────────────────────────────────────────────────────────
//  RELATION HELPERS
// ─────────────────────────────────────────────────────────

data class TrackWithPoints(
    @Embedded val track: TrackSession,
    @Relation(parentColumn = "id", entityColumn = "track_id")
    val points: List<TrackPoint>,
    @Relation(parentColumn = "id", entityColumn = "track_id")
    val healthData: List<HealthData>
)

// ─────────────────────────────────────────────────────────
//  SETTINGS
// ─────────────────────────────────────────────────────────

data class TrackingSettings(
    val recordIntervalMs: Long = 3000L,           // 3 seconds default
    val minDistanceMeters: Float = 5f,            // minimum displacement
    val autoDetectActivity: Boolean = true,
    val keepScreenOn: Boolean = false,
    val exportFormat: ExportFormat = ExportFormat.JSON
)

enum class ExportFormat { JSON, GPX }

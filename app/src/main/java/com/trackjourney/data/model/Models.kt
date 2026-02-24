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

    @ColumnInfo(name = "calories_burned")
    val caloriesBurned: Double = 0.0,

    @ColumnInfo(name = "battery_start")
    val batteryStart: Int? = null,

    @ColumnInfo(name = "battery_end")
    val batteryEnd: Int? = null,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "ride_cost")
    val rideCost: Double? = null
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
    val placeName: String? = null,

    @ColumnInfo(name = "satellites_used")
    val satellitesUsed: Int? = null,

    @ColumnInfo(name = "is_accurate")
    val isAccurate: Boolean = true
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
//  CAR PROFILE — Saved vehicle for ride cost calculation
// ─────────────────────────────────────────────────────────

@Entity(tableName = "car_profiles")
data class CarProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String = "",                          // display name, e.g. "My Tesla"

    @ColumnInfo(name = "car_model")
    val carModel: String = "",                      // e.g. "Toyota Camry"

    @ColumnInfo(name = "car_year")
    val carYear: Int = 2024,

    @ColumnInfo(name = "engine_size")
    val engineSize: Float = 0f,                     // liters (e.g. 2.0)

    @ColumnInfo(name = "is_electric")
    val isElectric: Boolean = false,

    // Gas car fields
    @ColumnInfo(name = "fuel_type")
    val fuelType: FuelType = FuelType.PETROL,

    @ColumnInfo(name = "fuel_price_per_liter")
    val fuelPricePerLiter: Float = 0f,              // local currency per liter

    @ColumnInfo(name = "fuel_consumption")
    val fuelConsumption: Float = 8f,                // liters per 100 km

    // Electric car fields
    @ColumnInfo(name = "battery_capacity_kwh")
    val batteryCapacityKwh: Float = 60f,            // kWh

    @ColumnInfo(name = "electric_consumption")
    val electricConsumption: Float = 15f,            // kWh per 100 km

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
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
    HIKING,
    UNKNOWN;

    companion object {
        fun fromSpeed(speedKmh: Float, configs: List<ActivityConfig>? = null): ActivityType {
            if (!configs.isNullOrEmpty()) {
                val activeConfigs = configs.filter { it.isActive }
                    .sortedBy { it.minSpeedKmh }
                for (cfg in activeConfigs) {
                    if (speedKmh >= cfg.minSpeedKmh && speedKmh < cfg.maxSpeedKmh) {
                        return try { valueOf(cfg.activityType) } catch (_: Exception) { UNKNOWN }
                    }
                }
            }
            return when {
                speedKmh < 0.5f    -> STATIONARY
                speedKmh < 7f      -> WALKING
                speedKmh < 15f     -> RUNNING
                speedKmh < 35f     -> CYCLING
                speedKmh < 200f    -> DRIVING
                else                -> FLYING
            }
        }
    }
}

data class ActivityConfig(
    val activityType: String,
    val displayName: String,
    val iconName: String,
    val minSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val colorHex: Long,
    val metValue: Double,
    val isActive: Boolean = true
) {
    companion object {
        fun defaults(): List<ActivityConfig> = listOf(
            ActivityConfig("STATIONARY", "Stationary", "RadioButtonUnchecked", 0f, 0.5f, 0xFF607D8B, 1.0),
            ActivityConfig("WALKING", "Walking", "DirectionsWalk", 0.5f, 6f, 0xFF4CAF50, 3.5),
            ActivityConfig("HIKING", "Hiking", "Terrain", 2f, 6f, 0xFF8BC34A, 4.5, isActive = false),
            ActivityConfig("RUNNING", "Running", "DirectionsRun", 6f, 15f, 0xFFFF9800, 8.0),
            ActivityConfig("CYCLING", "Cycling", "DirectionsBike", 15f, 35f, 0xFF2196F3, 6.0),
            ActivityConfig("DRIVING", "Driving", "DirectionsCar", 35f, 200f, 0xFF9C27B0, 0.0),
            ActivityConfig("FLYING", "Flying", "Flight", 200f, 999f, 0xFFE91E63, 1.0)
        )
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
    @SerializedName("end_place_name") val endPlaceName: String?,
    @SerializedName("calories_burned") val caloriesBurned: Double = 0.0,
    @SerializedName("battery_start") val batteryStart: Int? = null,
    @SerializedName("battery_end") val batteryEnd: Int? = null,
    @SerializedName("ride_cost") val rideCost: Double? = null
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
    @SerializedName("place_name") val placeName: String?,
    @SerializedName("satellites_used") val satellitesUsed: Int?,
    @SerializedName("is_accurate") val isAccurate: Boolean
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
//  FULL DATABASE EXPORT MODEL
// ─────────────────────────────────────────────────────────

data class FullDatabaseExport(
    @SerializedName("export_version") val exportVersion: Int = 1,
    @SerializedName("export_timestamp") val exportTimestamp: Long = System.currentTimeMillis(),
    @SerializedName("app_version") val appVersion: String = "1.0.0",
    @SerializedName("tracks") val tracks: List<TrackExport>,
    @SerializedName("track_count") val trackCount: Int = tracks.size,
    @SerializedName("total_points") val totalPoints: Int = tracks.sumOf { it.points.size }
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
    val exportFormat: ExportFormat = ExportFormat.JSON,
    val userName: String = "",
    val userWeightKg: Float = 70f,                // default 70 kg
    val userHeightCm: Float = 170f,               // default 170 cm
    val trackingMode: TrackingMode = TrackingMode.HIGH_ACCURACY,
    val selectedCarId: String? = null,             // ID of the active car profile
    val currency: String = "$",                    // currency symbol for ride cost
    val activityConfigs: List<ActivityConfig> = ActivityConfig.defaults()
)

enum class ExportFormat { JSON, GPX, CSV }

enum class TrackingMode(val label: String) {
    HIGH_ACCURACY("High Accuracy"),
    ENERGY_EFFICIENCY("Energy Efficiency"),
    AI_BATTERY_SAVER("AI Battery Saver")
}

enum class FuelType(val label: String) {
    PETROL("Petrol"),
    DIESEL("Diesel"),
    LPG("LPG")
}

package com.trackjourney.data.local

import androidx.room.*
import com.trackjourney.data.ai.models.LocalAiModelEntity
import com.trackjourney.data.ai.models.LocalAiModelDao
import com.trackjourney.data.model.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────
//  DATABASE
// ─────────────────────────────────────────────────────────

@Database(
    entities = [TrackSession::class, TrackPoint::class, HealthData::class, AiAnalysis::class, CarProfile::class, LocalAiModelEntity::class],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class TrackDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun healthDataDao(): HealthDataDao
    abstract fun aiAnalysisDao(): AiAnalysisDao
    abstract fun carProfileDao(): CarProfileDao
    abstract fun localAiModelDao(): LocalAiModelDao
}

// ─────────────────────────────────────────────────────────
//  TYPE CONVERTERS
// ─────────────────────────────────────────────────────────

class Converters {
    @TypeConverter
    fun fromActivityType(value: ActivityType): String = value.name

    @TypeConverter
    fun toActivityType(value: String): ActivityType =
        try { ActivityType.valueOf(value) } catch (e: Exception) { ActivityType.UNKNOWN }

    @TypeConverter
    fun fromWearableType(value: WearableType): String = value.name

    @TypeConverter
    fun toWearableType(value: String): WearableType =
        try { WearableType.valueOf(value) } catch (e: Exception) { WearableType.UNKNOWN }

    @TypeConverter
    fun fromFuelType(value: FuelType): String = value.name

    @TypeConverter
    fun toFuelType(value: String): FuelType =
        try { FuelType.valueOf(value) } catch (e: Exception) { FuelType.PETROL }
}

// ─────────────────────────────────────────────────────────
//  TRACK DAO
// ─────────────────────────────────────────────────────────

@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackSession)

    @Update
    suspend fun update(track: TrackSession)

    @Delete
    suspend fun delete(track: TrackSession)

    @Query("SELECT * FROM tracks ORDER BY start_time DESC")
    fun getAllTracks(): Flow<List<TrackSession>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: String): TrackSession?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observeTrackById(id: String): Flow<TrackSession?>

    @Query("SELECT * FROM tracks WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveTrack(): TrackSession?

    @Query("SELECT * FROM tracks WHERE is_active = 1 LIMIT 1")
    fun observeActiveTrack(): Flow<TrackSession?>

    @Transaction
    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackWithPoints(id: String): TrackWithPoints?

    @Transaction
    @Query("SELECT * FROM tracks ORDER BY start_time DESC")
    fun getAllTracksWithPoints(): Flow<List<TrackWithPoints>>

    @Transaction
    @Query("SELECT * FROM tracks ORDER BY start_time DESC")
    suspend fun getAllTracksWithPointsSync(): List<TrackWithPoints>

    @Query("""
        SELECT * FROM tracks 
        WHERE activity_type = :activityType 
        ORDER BY start_time DESC
    """)
    fun getTracksByActivity(activityType: ActivityType): Flow<List<TrackSession>>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTrackCount(): Int

    @Query("SELECT SUM(distance_meters) FROM tracks")
    suspend fun getTotalDistance(): Double?

    @Query("SELECT AVG(avg_speed_kmh) FROM tracks WHERE avg_speed_kmh > 0")
    suspend fun getAverageSpeed(): Double?

    @Query("SELECT COUNT(*) FROM tracks WHERE start_time >= :since")
    suspend fun getTrackCountSince(since: Long): Int

    @Query("SELECT SUM(distance_meters) FROM tracks WHERE start_time >= :since")
    suspend fun getTotalDistanceSince(since: Long): Double?

    @Query("SELECT AVG(avg_speed_kmh) FROM tracks WHERE start_time >= :since AND avg_speed_kmh > 0")
    suspend fun getAverageSpeedSince(since: Long): Double?

    @Query("SELECT MAX(max_speed_kmh) FROM tracks WHERE start_time >= :since")
    suspend fun getMaxSpeedSince(since: Long): Double?

    @Query("SELECT SUM(COALESCE(end_time, start_time) - start_time) FROM tracks WHERE start_time >= :since AND end_time IS NOT NULL")
    suspend fun getTotalDurationSince(since: Long): Long?

    @Query("SELECT SUM(calories_burned) FROM tracks WHERE start_time >= :since AND calories_burned > 0")
    suspend fun getTotalCaloriesSince(since: Long): Double?

    // ── All-time aggregate queries ──

    @Query("SELECT MAX(max_speed_kmh) FROM tracks")
    suspend fun getMaxSpeed(): Double?

    @Query("SELECT SUM(COALESCE(end_time, start_time) - start_time) FROM tracks WHERE end_time IS NOT NULL")
    suspend fun getTotalDuration(): Long?

    @Query("SELECT SUM(calories_burned) FROM tracks WHERE calories_burned > 0")
    suspend fun getTotalCalories(): Double?

    // ── Activity breakdown ──

    @Query("SELECT activity_type FROM tracks WHERE is_active = 0")
    suspend fun getAllActivityTypes(): List<ActivityType>

    @Query("SELECT COUNT(*) FROM tracks WHERE activity_type = :activityType AND is_active = 0")
    suspend fun getTrackCountByActivity(activityType: ActivityType): Int

    @Query("SELECT SUM(distance_meters) FROM tracks WHERE activity_type = :activityType AND is_active = 0")
    suspend fun getTotalDistanceByActivity(activityType: ActivityType): Double?

    @Query("SELECT SUM(COALESCE(end_time, start_time) - start_time) FROM tracks WHERE activity_type = :activityType AND is_active = 0 AND end_time IS NOT NULL")
    suspend fun getTotalDurationByActivity(activityType: ActivityType): Long?
}

// ─────────────────────────────────────────────────────────
//  TRACK POINT DAO
// ─────────────────────────────────────────────────────────

@Dao
interface TrackPointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: TrackPoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<TrackPoint>)

    @Query("SELECT * FROM track_points WHERE track_id = :trackId ORDER BY timestamp ASC")
    fun getPointsForTrack(trackId: String): Flow<List<TrackPoint>>

    @Query("SELECT * FROM track_points WHERE track_id = :trackId ORDER BY timestamp ASC")
    suspend fun getPointsForTrackSync(trackId: String): List<TrackPoint>

    @Query("SELECT * FROM track_points WHERE track_id = :trackId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPoint(trackId: String): TrackPoint?

    @Query("SELECT COUNT(*) FROM track_points WHERE track_id = :trackId")
    suspend fun getPointCount(trackId: String): Int

    @Query("SELECT AVG(speed_kmh) FROM track_points WHERE track_id = :trackId AND speed_kmh > 0")
    suspend fun getAverageSpeed(trackId: String): Float?

    @Query("SELECT MAX(speed_kmh) FROM track_points WHERE track_id = :trackId")
    suspend fun getMaxSpeed(trackId: String): Float?

    @Query("DELETE FROM track_points WHERE track_id = :trackId")
    suspend fun deletePointsForTrack(trackId: String)

    @Query("SELECT altitude FROM track_points WHERE altitude IS NOT NULL ORDER BY timestamp ASC")
    suspend fun getAllAltitudes(): List<Double>

    @Query("SELECT COUNT(*) FROM track_points")
    suspend fun getTotalPointCount(): Int
}

// ─────────────────────────────────────────────────────────
//  HEALTH DATA DAO
// ─────────────────────────────────────────────────────────

@Dao
interface HealthDataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: HealthData)

    @Query("SELECT * FROM health_data WHERE track_id = :trackId ORDER BY timestamp ASC")
    fun getHealthDataForTrack(trackId: String): Flow<List<HealthData>>

    @Query("SELECT * FROM health_data WHERE track_id = :trackId ORDER BY timestamp ASC")
    suspend fun getHealthDataForTrackSync(trackId: String): List<HealthData>

    @Query("SELECT AVG(heart_rate) FROM health_data WHERE track_id = :trackId AND heart_rate IS NOT NULL")
    suspend fun getAverageHeartRate(trackId: String): Int?

    @Query("SELECT AVG(cadence) FROM health_data WHERE track_id = :trackId AND cadence IS NOT NULL")
    suspend fun getAverageCadence(trackId: String): Int?
}

// ─────────────────────────────────────────────────────────
//  AI ANALYSIS DAO
// ─────────────────────────────────────────────────────────

@Dao
interface AiAnalysisDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(analysis: AiAnalysis)

    @Query("SELECT * FROM ai_analyses WHERE track_id = :trackId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestAnalysis(trackId: String): AiAnalysis?

    @Query("SELECT * FROM ai_analyses WHERE track_id = :trackId ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestAnalysis(trackId: String): Flow<AiAnalysis?>

    @Query("SELECT * FROM ai_analyses ORDER BY timestamp DESC")
    fun getAllAnalyses(): Flow<List<AiAnalysis>>
}

// ─────────────────────────────────────────────────────────
//  CAR PROFILE DAO
// ─────────────────────────────────────────────────────────

@Dao
interface CarProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(car: CarProfile)

    @Update
    suspend fun update(car: CarProfile)

    @Delete
    suspend fun delete(car: CarProfile)

    @Query("SELECT * FROM car_profiles ORDER BY created_at ASC")
    fun getAllCars(): Flow<List<CarProfile>>

    @Query("SELECT * FROM car_profiles WHERE id = :id")
    suspend fun getCarById(id: String): CarProfile?

    @Query("SELECT * FROM car_profiles WHERE id = :id")
    fun observeCarById(id: String): Flow<CarProfile?>

    @Query("SELECT COUNT(*) FROM car_profiles")
    suspend fun getCarCount(): Int
}

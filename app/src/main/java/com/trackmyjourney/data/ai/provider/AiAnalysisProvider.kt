package com.trackmyjourney.data.ai.provider

import com.trackmyjourney.data.ai.models.AiExecutionMode
import com.trackmyjourney.data.model.TrackWithPoints

/**
 * Lifetime context from historical tracks, passed to AI providers for
 * comparative analysis ("this trip vs your average").
 */
data class LifetimeContext(
    val totalTracks: Int,
    val totalDistanceKm: Double,
    val avgDistanceKm: Double,
    val avgSpeedKmh: Double,
    val avgDurationMin: Long,
    val totalCalories: Double,
    val avgCaloriesPerTrip: Double,
    val bestDistanceKm: Double,
    val bestSpeedKmh: Double,
    val sameActivityCount: Int,
    val sameActivityAvgSpeedKmh: Double,
    val sameActivityAvgDistanceKm: Double
)

interface AiAnalysisProvider {
    val executionMode: AiExecutionMode
    val displayName: String
    suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints, lifetimeContext: LifetimeContext? = null): String
    suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String
    fun isConfigured(): Boolean
    fun isAvailable(): Boolean = isConfigured()
}

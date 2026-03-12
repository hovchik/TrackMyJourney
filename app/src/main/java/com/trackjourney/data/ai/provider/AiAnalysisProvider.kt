package com.trackjourney.data.ai.provider

import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.model.TrackWithPoints

interface AiAnalysisProvider {
    val executionMode: AiExecutionMode
    val displayName: String
    suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints): String
    suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String
    fun isConfigured(): Boolean
    fun isAvailable(): Boolean = isConfigured()
}

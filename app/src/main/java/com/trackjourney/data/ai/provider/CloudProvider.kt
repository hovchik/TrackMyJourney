package com.trackjourney.data.ai.provider

import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.model.TrackWithPoints
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud-based AI provider that sends summarized track data to a cloud API
 * (e.g., Claude, GPT) for analysis.
 */
@Singleton
class CloudProvider @Inject constructor() : AiAnalysisProvider {

    override val executionMode: AiExecutionMode = AiExecutionMode.CLOUD
    override val displayName: String = "Cloud AI"

    // API key would be stored securely and injected
    private var apiKey: String? = null

    fun setApiKey(key: String) {
        apiKey = key
    }

    override fun isConfigured(): Boolean = !apiKey.isNullOrBlank()

    override fun isAvailable(): Boolean = isConfigured()

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints): String {
        if (!isConfigured()) {
            throw IllegalStateException("Cloud AI provider not configured. Set API key first.")
        }
        // Placeholder: In production, this would call a cloud AI API
        // with summarized track data (not raw GPS) for privacy
        val summary = buildTrackSummary(snapshot)
        return """{"source": "cloud_ai", "analysis": "Cloud AI analysis placeholder", "track_summary": "$summary"}"""
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        if (!isConfigured()) {
            throw IllegalStateException("Cloud AI provider not configured. Set API key first.")
        }
        return """{"source": "cloud_ai", "analysis": "Weekly cloud AI analysis placeholder", "track_count": ${snapshots.size}}"""
    }

    private fun buildTrackSummary(snapshot: TrackWithPoints): String {
        val track = snapshot.track
        return "distance=${track.distanceMeters}m, " +
                "avgSpeed=${track.avgSpeedKmh}km/h, " +
                "activity=${track.activityType}"
    }
}

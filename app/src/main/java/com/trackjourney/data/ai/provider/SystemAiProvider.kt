package com.trackjourney.data.ai.provider

import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.runtime.SystemAiRuntimeAdapter
import com.trackjourney.data.model.TrackWithPoints
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemAiProvider @Inject constructor(
    private val systemRuntime: SystemAiRuntimeAdapter
) : AiAnalysisProvider {

    override val executionMode: AiExecutionMode = AiExecutionMode.SYSTEM_LOCAL
    override val displayName: String = "System AI (AICore)"

    override fun isConfigured(): Boolean = systemRuntime.isAvailable()
    override fun isAvailable(): Boolean = systemRuntime.isAvailable()

    fun getStatusMessage(): String = systemRuntime.getStatusMessage()

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints): String {
        val prompt = buildDailyPrompt(snapshot)
        return systemRuntime.runPrompt(prompt)
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        val prompt = buildWeeklyPrompt(snapshots)
        return systemRuntime.runPrompt(prompt)
    }

    private fun buildDailyPrompt(snapshot: TrackWithPoints): String {
        val track = snapshot.track
        return """Analyze this journey track and provide insights as JSON:
            |Distance: ${track.distanceMeters} meters
            |Average Speed: ${track.avgSpeedKmh} km/h
            |Max Speed: ${track.maxSpeedKmh} km/h
            |Activity: ${track.activityType}
            |Duration: ${(track.endTime ?: System.currentTimeMillis()) - track.startTime} ms
            |Points: ${snapshot.points.size}
        """.trimMargin()
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        val summaries = snapshots.joinToString("\n") { twp ->
            "- ${twp.track.activityType}: ${twp.track.distanceMeters}m, ${twp.track.avgSpeedKmh}km/h"
        }
        return """Analyze these ${snapshots.size} journey tracks from the past week and provide weekly insights as JSON:
            |$summaries
        """.trimMargin()
    }
}

package com.trackjourney.data.ai.provider

import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.LocalModelManager
import com.trackjourney.data.ai.runtime.LiteRtRuntimeAdapter
import com.trackjourney.data.ai.runtime.LocalModelRuntime
import com.trackjourney.data.ai.runtime.MediaPipeLlmRuntimeAdapter
import com.trackjourney.data.model.TrackWithPoints
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomLocalModelProvider @Inject constructor(
    private val modelManager: LocalModelManager,
    private val mediaPipeRuntime: MediaPipeLlmRuntimeAdapter,
    private val liteRtRuntime: LiteRtRuntimeAdapter
) : AiAnalysisProvider {

    override val executionMode: AiExecutionMode = AiExecutionMode.CUSTOM_LOCAL
    override val displayName: String = "Local Model"

    override fun isConfigured(): Boolean = modelManager.getActiveModelSync() != null

    override fun isAvailable(): Boolean {
        if (!isConfigured()) return false
        val activeModel = modelManager.getActiveModelSync() ?: return false
        val runtime = resolveRuntime(activeModel.runtimeType)
        return runtime?.isAvailable() == true
    }

    private fun resolveRuntime(runtimeType: String): LocalModelRuntime? {
        return when (runtimeType) {
            "mediapipe_llm" -> mediaPipeRuntime
            "litert" -> liteRtRuntime
            else -> null
        }
    }

    override suspend fun analyzeDailyBehavior(snapshot: TrackWithPoints): String {
        val activeModel = modelManager.getActiveModelSync()
            ?: throw IllegalStateException("No active local model configured")
        val runtime = resolveRuntime(activeModel.runtimeType)
            ?: throw IllegalStateException("No runtime available for ${activeModel.runtimeType}")

        // Load model if needed
        if (!runtime.isAvailable() && activeModel.localPath != null) {
            when (runtime) {
                is MediaPipeLlmRuntimeAdapter -> runtime.loadModel(activeModel.localPath)
                is LiteRtRuntimeAdapter -> runtime.loadModel(activeModel.localPath)
            }
        }

        val prompt = buildDailyPrompt(snapshot)
        val rawOutput = runtime.runPrompt(prompt)
        return extractJson(rawOutput)
    }

    override suspend fun analyzeWeeklyBehavior(snapshots: List<TrackWithPoints>): String {
        val activeModel = modelManager.getActiveModelSync()
            ?: throw IllegalStateException("No active local model configured")
        val runtime = resolveRuntime(activeModel.runtimeType)
            ?: throw IllegalStateException("No runtime available for ${activeModel.runtimeType}")

        if (!runtime.isAvailable() && activeModel.localPath != null) {
            when (runtime) {
                is MediaPipeLlmRuntimeAdapter -> runtime.loadModel(activeModel.localPath)
                is LiteRtRuntimeAdapter -> runtime.loadModel(activeModel.localPath)
            }
        }

        val prompt = buildWeeklyPrompt(snapshots)
        val rawOutput = runtime.runPrompt(prompt)
        return extractJson(rawOutput)
    }

    private fun buildDailyPrompt(snapshot: TrackWithPoints): String {
        val track = snapshot.track
        return """You are a journey analysis AI. Analyze this track data and return a JSON object with keys: activity, summary, suggestions (array), confidence (0-1).

Track Data:
- Distance: ${track.distanceMeters} meters
- Average Speed: ${track.avgSpeedKmh} km/h
- Max Speed: ${track.maxSpeedKmh} km/h
- Activity Type: ${track.activityType}
- Duration: ${(track.endTime ?: System.currentTimeMillis()) - track.startTime} ms
- GPS Points: ${snapshot.points.size}
- Calories: ${track.caloriesBurned}

Return valid JSON only."""
    }

    private fun buildWeeklyPrompt(snapshots: List<TrackWithPoints>): String {
        val summaries = snapshots.joinToString("\n") { twp ->
            "- ${twp.track.activityType}: ${twp.track.distanceMeters}m, ${twp.track.avgSpeedKmh}km/h, ${twp.track.caloriesBurned} cal"
        }
        return """You are a journey analysis AI. Analyze these ${snapshots.size} tracks from the past week. Return a JSON object with keys: totalDistance, totalCalories, dominantActivity, weekSummary, improvements (array).

Tracks:
$summaries

Return valid JSON only."""
    }

    private fun extractJson(output: String): String {
        // Try to extract JSON from output that may contain extra text
        val jsonStart = output.indexOf('{')
        val jsonEnd = output.lastIndexOf('}')
        return if (jsonStart >= 0 && jsonEnd > jsonStart) {
            output.substring(jsonStart, jsonEnd + 1)
        } else {
            output
        }
    }
}

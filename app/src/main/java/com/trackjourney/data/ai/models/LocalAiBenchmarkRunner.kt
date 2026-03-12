package com.trackjourney.data.ai.models

import android.app.ActivityManager
import android.content.Context
import com.trackjourney.data.ai.runtime.LiteRtRuntimeAdapter
import com.trackjourney.data.ai.runtime.LocalModelRuntime
import com.trackjourney.data.ai.runtime.MediaPipeLlmRuntimeAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalAiBenchmarkRunner @Inject constructor(
    private val context: Context,
    private val modelManager: LocalModelManager,
    private val mediaPipeRuntime: MediaPipeLlmRuntimeAdapter,
    private val liteRtRuntime: LiteRtRuntimeAdapter
) {
    companion object {
        private const val TEST_PROMPT = "Analyze this journey: walked 2.5km in 30 minutes at 5km/h average speed. Summarize in JSON."
        private const val ESTIMATED_OUTPUT_TOKENS = 50
    }

    suspend fun runBenchmark(): BenchmarkResult? = withContext(Dispatchers.IO) {
        val activeModel = modelManager.getActiveModel() ?: return@withContext null

        val runtime = resolveRuntime(activeModel) ?: return@withContext null

        // Load model if needed
        if (!runtime.isAvailable() && activeModel.localPath != null) {
            when (runtime) {
                is MediaPipeLlmRuntimeAdapter -> runtime.loadModel(activeModel.localPath)
                is LiteRtRuntimeAdapter -> runtime.loadModel(activeModel.localPath)
            }
        }

        if (!runtime.isAvailable()) return@withContext null

        val memBefore = getUsedMemoryMb()

        val startTime = System.currentTimeMillis()
        try {
            runtime.runPrompt(TEST_PROMPT)
        } catch (e: Exception) {
            return@withContext BenchmarkResult(
                modelId = activeModel.modelId,
                latencyMs = -1,
                tokensPerSecond = 0f,
                memoryBeforeMb = memBefore,
                memoryAfterMb = memBefore,
                memoryDeltaMb = 0,
                performanceRating = "Error: ${e.message}"
            )
        }
        val latencyMs = System.currentTimeMillis() - startTime

        val memAfter = getUsedMemoryMb()
        val tokensPerSecond = if (latencyMs > 0) {
            (ESTIMATED_OUTPUT_TOKENS * 1000f) / latencyMs
        } else 0f

        val rating = when {
            tokensPerSecond >= 15 -> "Excellent"
            tokensPerSecond >= 8 -> "Good"
            tokensPerSecond >= 3 -> "Adequate"
            else -> "Slow"
        }

        BenchmarkResult(
            modelId = activeModel.modelId,
            latencyMs = latencyMs,
            tokensPerSecond = tokensPerSecond,
            memoryBeforeMb = memBefore,
            memoryAfterMb = memAfter,
            memoryDeltaMb = memAfter - memBefore,
            performanceRating = rating
        )
    }

    private fun resolveRuntime(model: LocalAiModel): LocalModelRuntime? {
        return when (model.runtimeType) {
            "mediapipe_llm" -> mediaPipeRuntime
            "litert" -> liteRtRuntime
            else -> null
        }
    }

    private fun getUsedMemoryMb(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
    }
}

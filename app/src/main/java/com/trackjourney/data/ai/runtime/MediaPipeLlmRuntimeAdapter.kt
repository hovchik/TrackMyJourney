package com.trackjourney.data.ai.runtime

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime adapter for MediaPipe LLM Inference API.
 * Loads .task format models for on-device text generation.
 */
@Singleton
class MediaPipeLlmRuntimeAdapter @Inject constructor(
    private val context: Context
) : LocalModelRuntime {

    companion object {
        private const val TAG = "MediaPipeLlmRuntime"
        // Safe default — must be lower than smallest model's KV cache (ekv1280).
        // MediaPipe maxTokens = total context (prompt + response), not just response.
        private const val DEFAULT_MAX_TOKENS = 512
    }

    override val runtimeId: String = "mediapipe_llm"
    override val displayName: String = "MediaPipe LLM"

    private var modelPath: String? = null
    private var llmInference: LlmInference? = null
    private var isLoaded: Boolean = false

    /**
     * Extracts the KV cache size from a string (path or URL).
     * e.g. "...ekv1280.task" -> 1280, "...ekv4096..." -> 4096
     */
    private fun extractKvCacheSize(vararg sources: String?): Int {
        for (source in sources) {
            if (source == null) continue
            val match = Regex("ekv(\\d+)").find(source)
            val size = match?.groupValues?.get(1)?.toIntOrNull()
            if (size != null) {
                Log.i(TAG, "Detected KV cache size: $size tokens (from: ${source.takeLast(60)})")
                return size
            }
        }
        Log.i(TAG, "No KV cache size found, using safe default: $DEFAULT_MAX_TOKENS tokens")
        return DEFAULT_MAX_TOKENS
    }

    fun loadModel(path: String, downloadUrl: String? = null) {
        // Release previous model if any
        release()

        modelPath = path
        val maxTokens = extractKvCacheSize(path, downloadUrl)
        Log.i(TAG, "Loading model from: $path (maxTokens=$maxTokens)")
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(maxTokens)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isLoaded = true
            Log.i(TAG, "Model loaded successfully from: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from: $path", e)
            isLoaded = false
            llmInference = null
            throw e
        }
    }

    override fun isAvailable(): Boolean = isLoaded && llmInference != null

    override suspend fun runPrompt(prompt: String): String = withContext(Dispatchers.IO) {
        val inference = llmInference
            ?: throw IllegalStateException("MediaPipe LLM model not loaded. Call loadModel() first.")

        Log.d(TAG, "Generating response for prompt (${prompt.length} chars)")
        try {
            val response = inference.generateResponse(prompt)
            Log.d(TAG, "Generated response (${response.length} chars)")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            throw e
        }
    }

    override fun supportsStructuredJson(): Boolean = true

    override fun supportsStreaming(): Boolean = true

    override fun release() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing LLM inference", e)
        }
        llmInference = null
        isLoaded = false
        modelPath = null
    }
}

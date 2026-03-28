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
        private const val DEFAULT_MAX_TOKENS = 1024
    }

    override val runtimeId: String = "mediapipe_llm"
    override val displayName: String = "MediaPipe LLM"

    private var modelPath: String? = null
    private var llmInference: LlmInference? = null
    private var isLoaded: Boolean = false

    /**
     * Extracts the KV cache size from the model filename.
     * e.g. "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task" -> 1280
     * Falls back to [DEFAULT_MAX_TOKENS] if not found.
     */
    private fun extractMaxTokensFromPath(path: String): Int {
        val ekvMatch = Regex("ekv(\\d+)").find(path)
        val ekvSize = ekvMatch?.groupValues?.get(1)?.toIntOrNull()
        if (ekvSize != null) {
            Log.i(TAG, "Detected KV cache size from filename: $ekvSize tokens")
            return ekvSize
        }
        Log.i(TAG, "No KV cache size in filename, using default: $DEFAULT_MAX_TOKENS tokens")
        return DEFAULT_MAX_TOKENS
    }

    fun loadModel(path: String) {
        // Release previous model if any
        release()

        modelPath = path
        val maxTokens = extractMaxTokensFromPath(path)
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

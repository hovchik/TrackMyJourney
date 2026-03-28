package com.trackjourney.data.ai.runtime

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime adapter for LiteRT / TFLite models.
 *
 * Note: Standard TFLite Interpreter does not support autoregressive text generation
 * (LLM inference). For GGUF models, this adapter delegates to MediaPipe LLM Inference
 * which provides proper token-by-token generation on device.
 *
 * This adapter exists for forward-compatibility if Google releases dedicated
 * TFLite-based LLM runtimes, but currently uses the same MediaPipe backend.
 */
@Singleton
class LiteRtRuntimeAdapter @Inject constructor(
    private val context: Context
) : LocalModelRuntime {

    companion object {
        private const val TAG = "LiteRtRuntime"
        private const val MAX_OUTPUT_TOKENS = 2048
    }

    override val runtimeId: String = "litert"
    override val displayName: String = "LiteRT (TFLite)"

    private var modelPath: String? = null
    private var llmInference: LlmInference? = null
    private var isLoaded: Boolean = false

    fun loadModel(path: String, downloadUrl: String? = null) {
        release()

        modelPath = path
        Log.i(TAG, "Loading model from: $path")
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_OUTPUT_TOKENS)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isLoaded = true
            Log.i(TAG, "Model loaded successfully from: $path (maxTokens=$MAX_OUTPUT_TOKENS)")
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
            ?: throw IllegalStateException("LiteRT model not loaded. Call loadModel() first.")

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

    override fun supportsStructuredJson(): Boolean = false

    override fun supportsStreaming(): Boolean = false

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

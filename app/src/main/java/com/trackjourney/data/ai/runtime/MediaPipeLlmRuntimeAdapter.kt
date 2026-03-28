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
 * Loads GGUF-format models for on-device text generation.
 */
@Singleton
class MediaPipeLlmRuntimeAdapter @Inject constructor(
    private val context: Context
) : LocalModelRuntime {

    companion object {
        private const val TAG = "MediaPipeLlmRuntime"
        private const val MAX_TOKENS = 4096
    }

    override val runtimeId: String = "mediapipe_llm"
    override val displayName: String = "MediaPipe LLM"

    private var modelPath: String? = null
    private var llmInference: LlmInference? = null
    private var isLoaded: Boolean = false

    fun loadModel(path: String) {
        // Release previous model if any
        release()

        modelPath = path
        Log.i(TAG, "Loading model from: $path")
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_TOKENS)
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

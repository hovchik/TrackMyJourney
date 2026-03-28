package com.trackjourney.data.ai.runtime

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
        private const val MAX_OUTPUT_TOKENS = 2048
        private const val TEMPERATURE = 0.3f
    }

    override val runtimeId: String = "mediapipe_llm"
    override val displayName: String = "MediaPipe LLM"

    private var modelPath: String? = null
    private var llmInference: LlmInference? = null
    private var isLoaded: Boolean = false

    fun loadModel(path: String, downloadUrl: String? = null) {
        // Release previous model if any
        release()

        modelPath = path
        Log.i(TAG, "Loading model from: $path")
        try {
            // Clear stale XNNPACK weight cache that may have been built with
            // different parameters. A corrupt/mismatched cache causes
            // "Cannot reserve space in a cache that isn't building" SIGABRT.
            clearXnnpackCache()

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_OUTPUT_TOKENS)
                .setTemperature(TEMPERATURE)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isLoaded = true
            Log.i(TAG, "Model loaded successfully from: $path (maxTokens=$MAX_OUTPUT_TOKENS, temp=$TEMPERATURE)")
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

    /**
     * Deletes any XNNPACK weight cache files from the app's cache directory.
     * MediaPipe caches compiled XNNPACK weights as `*.xnnpack_cache` files.
     * A stale cache (from a different model or different config) causes a
     * native SIGABRT: "Cannot reserve space in a cache that isn't building."
     */
    private fun clearXnnpackCache() {
        try {
            val cacheDir = context.cacheDir
            val cacheFiles = cacheDir.listFiles { file ->
                file.name.endsWith(".xnnpack_cache")
            }
            cacheFiles?.forEach { file ->
                Log.i(TAG, "Deleting stale XNNPACK cache: ${file.name} (${file.length() / 1024}KB)")
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear XNNPACK cache: ${e.message}")
        }
    }

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

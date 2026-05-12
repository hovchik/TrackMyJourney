package com.trackmyjourney.data.ai.runtime

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        /** Fallback context window when we can't read the model's ekv hint. */
        private const val DEFAULT_CONTEXT_TOKENS = 1280

        /** Rough chars-per-token estimate for English text. */
        private const val CHARS_PER_TOKEN = 4

        /**
         * Tokens reserved for the model's own output during generation.
         * Subtracted from the context budget so the prompt never crowds out
         * the response.
         */
        private const val OUTPUT_TOKEN_RESERVE = 512

        /** Reads `_ekv####` from the .task filename if present. */
        private val EKV_REGEX = Regex("_ekv(\\d+)")
    }

    override val runtimeId: String = "mediapipe_llm"
    override val displayName: String = "MediaPipe LLM"

    private var modelPath: String? = null
    private var llmInference: LlmInference? = null
    private var isLoaded: Boolean = false
    private var contextTokens: Int = DEFAULT_CONTEXT_TOKENS

    // MediaPipe's LlmInference is not safe to call concurrently from
    // multiple coroutines — overlapping predictSync calls can SIGABRT.
    private val inferenceMutex = Mutex()

    fun loadModel(path: String, downloadUrl: String? = null) {
        // Release previous model if any
        release()

        modelPath = path
        contextTokens = detectContextTokens(path, downloadUrl)
        Log.i(TAG, "Loading model from: $path (ekv=$contextTokens)")
        try {
            // Clear stale XNNPACK weight cache that may have been built with
            // different parameters. A corrupt/mismatched cache causes
            // "Cannot reserve space in a cache that isn't building" SIGABRT.
            clearXnnpackCache()

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                // setMaxTokens is the model's TOTAL context (input + output)
                // and must not exceed the .task file's compiled KV-cache size
                // (the `_ekv####` value).  Going over SIGABRTs in native code.
                .setMaxTokens(contextTokens)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isLoaded = true
            Log.i(TAG, "Model loaded successfully from: $path (maxTokens=$contextTokens)")
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

        val safePrompt = truncateForContext(prompt)
        if (safePrompt.length < prompt.length) {
            Log.w(TAG, "Prompt truncated ${prompt.length} → ${safePrompt.length} chars to fit context ($contextTokens tokens)")
        }

        Log.d(TAG, "Generating response for prompt (${safePrompt.length} chars, ctx=$contextTokens tokens)")
        inferenceMutex.withLock {
            try {
                val response = inference.generateResponse(safePrompt)
                Log.d(TAG, "Generated response (${response.length} chars)")
                response
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                throw e
            }
        }
    }

    override fun supportsStructuredJson(): Boolean = true

    override fun supportsStreaming(): Boolean = true

    /**
     * Trim the prompt to fit inside the model's context window, leaving room
     * for the model's response.  Cuts from the middle (keeps the leading
     * instructions and the trailing "Output JSON only" sentinel) so structural
     * cues at both ends survive.
     */
    private fun truncateForContext(prompt: String): String {
        val budgetTokens = (contextTokens - OUTPUT_TOKEN_RESERVE).coerceAtLeast(256)
        val budgetChars = budgetTokens * CHARS_PER_TOKEN
        if (prompt.length <= budgetChars) return prompt

        val keepHead = (budgetChars * 0.6).toInt()
        val keepTail = budgetChars - keepHead - 32
        if (keepTail <= 0) return prompt.take(budgetChars)

        return buildString {
            append(prompt, 0, keepHead)
            append("\n…[trimmed]…\n")
            append(prompt, prompt.length - keepTail, prompt.length)
        }
    }

    /**
     * Pulls the `_ekv####` hint out of the model filename (or download URL)
     * to size the KV cache correctly.  Falls back to [DEFAULT_CONTEXT_TOKENS]
     * when no hint is present.
     */
    private fun detectContextTokens(path: String, downloadUrl: String?): Int {
        val candidates = listOfNotNull(path, downloadUrl)
        for (candidate in candidates) {
            EKV_REGEX.find(candidate)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return DEFAULT_CONTEXT_TOKENS
    }

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

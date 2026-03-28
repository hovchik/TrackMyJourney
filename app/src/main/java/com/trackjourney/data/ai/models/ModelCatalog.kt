package com.trackjourney.data.ai.models

/**
 * Curated catalog of local AI models for on-device inference.
 *
 * All models are:
 * - MediaPipe `.task` format (TFLite FlatBuffer bundles, compatible with MediaPipe LLM Inference)
 * - Under 5 GB download size
 * - Downloadable from Hugging Face without authentication
 * - From the Qwen or DeepSeek model families
 *
 * Note: Gemma models require accepting Google's license on Hugging Face (gated access)
 * and cannot be included for auth-free download.
 */
object ModelCatalog {

    val availableModels: List<LocalAiModel> = listOf(
        // ── Qwen 2.5 0.5B Instruct ───────────────────────────
        // Source: litert-community/Qwen2.5-0.5B-Instruct (Apache 2.0)
        // Smallest & fastest model — good for low-end devices
        LocalAiModel(
            modelId = "qwen2.5-0.5b-instruct-q8",
            displayName = "Qwen 2.5 0.5B Instruct Q8 (Fastest)",
            runtimeType = "mediapipe_llm",
            fileFormat = "task",
            quantization = "Q8",
            requiredRamMb = 1024,
            recommendedRamMb = 1536,
            sizeMb = 547,
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "2.5",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),

        // ── Qwen 2.5 1.5B Instruct ──────────────────────────
        // Source: litert-community/Qwen2.5-1.5B-Instruct (Apache 2.0)
        // Good balance of speed and quality
        LocalAiModel(
            modelId = "qwen2.5-1.5b-instruct-q8",
            displayName = "Qwen 2.5 1.5B Instruct Q8",
            runtimeType = "mediapipe_llm",
            fileFormat = "task",
            quantization = "Q8",
            requiredRamMb = 2048,
            recommendedRamMb = 2560,
            sizeMb = 1600,
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "2.5",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),

        // ── DeepSeek R1 Distill Qwen 1.5B ────────────────────
        // Source: litert-community/DeepSeek-R1-Distill-Qwen-1.5B (MIT license)
        // Benchmarked on Samsung S24 Ultra
        LocalAiModel(
            modelId = "deepseek-r1-distill-qwen-1.5b-q8",
            displayName = "DeepSeek R1 Distill 1.5B Q8 (Recommended)",
            runtimeType = "mediapipe_llm",
            fileFormat = "task",
            quantization = "Q8",
            requiredRamMb = 2500,
            recommendedRamMb = 3072,
            sizeMb = 1860,
            downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "1.0",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),

        // ── Qwen3 4B Thinking ─────────────────────────────────
        // Source: harithoppil/qwen3-4b-thinking-litert (Apache 2.0)
        // Optimized for on-device inference with MediaPipe LLM Inference API
        LocalAiModel(
            modelId = "qwen3-4b-thinking-q4",
            displayName = "Qwen3 4B Thinking Q4",
            runtimeType = "mediapipe_llm",
            fileFormat = "task",
            quantization = "Q4_block128",
            requiredRamMb = 3584,
            recommendedRamMb = 4096,
            sizeMb = 2100,
            downloadUrl = "https://huggingface.co/harithoppil/qwen3-4b-thinking-litert/resolve/main/qwen3_thinking_4b_q4_block128_ekv2048.task",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "3.0",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        )
    )

    fun findById(modelId: String): LocalAiModel? = availableModels.find { it.modelId == modelId }

    fun getSmallModels(): List<LocalAiModel> = availableModels.filter { it.sizeMb < 1000 }
    fun getMediumModels(): List<LocalAiModel> = availableModels.filter { it.sizeMb >= 1000 }
}

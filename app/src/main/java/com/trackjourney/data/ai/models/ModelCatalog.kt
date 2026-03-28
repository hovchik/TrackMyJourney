package com.trackjourney.data.ai.models

/**
 * Curated catalog of local AI models for on-device inference.
 *
 * All models are:
 * - GGUF format (compatible with MediaPipe LLM Inference)
 * - Under 5 GB download size
 * - Downloadable from Hugging Face without authentication
 * - From the Gemma, Qwen, or DeepSeek model families
 *
 * Models are organized by size tier: Small (<1 GB), Medium (1-3 GB), Large (3-5 GB).
 * Within each tier, the fastest (smallest) option is listed first.
 */
object ModelCatalog {

    val availableModels: List<LocalAiModel> = listOf(
        // ── Small models (< 1 GB) — fastest on device ─────────
        LocalAiModel(
            modelId = "qwen2.5-0.5b-instruct-q4",
            displayName = "Qwen 2.5 0.5B Q4 (Fastest)",
            runtimeType = "mediapipe_llm",
            fileFormat = "bin",
            quantization = "Q4_K_M",
            requiredRamMb = 1024,
            recommendedRamMb = 1536,
            sizeMb = 400,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "2.5",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),
        LocalAiModel(
            modelId = "deepseek-r1-distill-qwen-1.5b-q4",
            displayName = "DeepSeek R1 Distill 1.5B Q4",
            runtimeType = "mediapipe_llm",
            fileFormat = "bin",
            quantization = "Q4_K_M",
            requiredRamMb = 1500,
            recommendedRamMb = 2048,
            sizeMb = 950,
            downloadUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "1.0",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),
        LocalAiModel(
            modelId = "qwen2.5-1.5b-instruct-q4",
            displayName = "Qwen 2.5 1.5B Q4",
            runtimeType = "mediapipe_llm",
            fileFormat = "bin",
            quantization = "Q4_K_M",
            requiredRamMb = 1500,
            recommendedRamMb = 2048,
            sizeMb = 990,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "2.5",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),

        // ── Medium models (1–3 GB) — recommended tier ─────────
        LocalAiModel(
            modelId = "gemma-2-2b-it-q4",
            displayName = "Gemma 2 2B Instruct Q4",
            runtimeType = "mediapipe_llm",
            fileFormat = "bin",
            quantization = "Q4_K_M",
            requiredRamMb = 2048,
            recommendedRamMb = 3072,
            sizeMb = 1600,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "2.0",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),
        LocalAiModel(
            modelId = "qwen2.5-3b-instruct-q4",
            displayName = "Qwen 2.5 3B Instruct Q4 (Recommended)",
            runtimeType = "mediapipe_llm",
            fileFormat = "bin",
            quantization = "Q4_K_M",
            requiredRamMb = 3072,
            recommendedRamMb = 4096,
            sizeMb = 2100,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "2.5",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),
        LocalAiModel(
            modelId = "gemma-2-2b-it-q8",
            displayName = "Gemma 2 2B Instruct Q8 (Higher Quality)",
            runtimeType = "mediapipe_llm",
            fileFormat = "bin",
            quantization = "Q8_0",
            requiredRamMb = 3072,
            recommendedRamMb = 4096,
            sizeMb = 2800,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q8_0.gguf",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "2.0",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),

        // ── Large models (3–5 GB) — best quality ─────────────
        LocalAiModel(
            modelId = "qwen2.5-7b-instruct-q4",
            displayName = "Qwen 2.5 7B Instruct Q4",
            runtimeType = "mediapipe_llm",
            fileFormat = "bin",
            quantization = "Q4_K_M",
            requiredRamMb = 5120,
            recommendedRamMb = 6144,
            sizeMb = 4700,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "2.5",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),
        LocalAiModel(
            modelId = "deepseek-r1-distill-qwen-7b-q3",
            displayName = "DeepSeek R1 Distill 7B Q3",
            runtimeType = "mediapipe_llm",
            fileFormat = "bin",
            quantization = "Q3_K_M",
            requiredRamMb = 4608,
            recommendedRamMb = 6144,
            sizeMb = 3600,
            downloadUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q3_K_M.gguf",
            localPath = null,
            installState = ModelInstallState.NOT_INSTALLED,
            checksum = null,
            version = "1.0",
            supportsStructuredJson = true,
            supportsStreaming = true,
            supportsTextGeneration = true
        ),
        LocalAiModel(
            modelId = "gemma-3-4b-it-q4",
            displayName = "Gemma 3 4B Instruct Q4",
            runtimeType = "mediapipe_llm",
            fileFormat = "bin",
            quantization = "Q4_K_M",
            requiredRamMb = 4096,
            recommendedRamMb = 5120,
            sizeMb = 2900,
            downloadUrl = "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf",
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
    fun getMediumModels(): List<LocalAiModel> = availableModels.filter { it.sizeMb in 1000..3000 }
    fun getLargeModels(): List<LocalAiModel> = availableModels.filter { it.sizeMb > 3000 }
}

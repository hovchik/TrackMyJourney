package com.trackmyjourney.data.ai.models

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelCompatibilityValidator @Inject constructor(
    private val detector: DeviceAiCapabilityDetector
) {
    fun validate(model: LocalAiModel): CompatibilityReport {
        val capability = detector.detect()
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // RAM check
        if (capability.totalRamMb < model.requiredRamMb) {
            issues.add(
                "Insufficient RAM: device has ${capability.totalRamMb} MB, " +
                        "model requires ${model.requiredRamMb} MB."
            )
        } else if (capability.totalRamMb < model.recommendedRamMb) {
            warnings.add(
                "RAM is below recommended: device has ${capability.totalRamMb} MB, " +
                        "recommended ${model.recommendedRamMb} MB. Performance may be reduced."
            )
        }

        // Storage check (model size + 200 MB buffer)
        val requiredStorage = model.sizeMb + 200
        if (capability.availableStorageMb < requiredStorage) {
            issues.add(
                "Insufficient storage: ${capability.availableStorageMb} MB available, " +
                        "need ${requiredStorage} MB (${model.sizeMb} MB model + 200 MB buffer)."
            )
        }

        // ABI check
        val supportedAbis = capability.supportedAbis
        when (model.runtimeType) {
            "mediapipe_llm" -> {
                if ("arm64-v8a" !in supportedAbis) {
                    issues.add("MediaPipe LLM requires arm64-v8a architecture. Device supports: ${supportedAbis.joinToString()}")
                }
            }
            "litert" -> {
                if ("arm64-v8a" !in supportedAbis && "armeabi-v7a" !in supportedAbis) {
                    issues.add("LiteRT requires arm64-v8a or armeabi-v7a. Device supports: ${supportedAbis.joinToString()}")
                }
            }
        }

        // SDK check
        if (model.runtimeType == "mediapipe_llm" && Build.VERSION.SDK_INT < 24) {
            issues.add("MediaPipe LLM requires Android API 24+. Current: ${Build.VERSION.SDK_INT}")
        }

        // Capability check
        if (!model.supportsTextGeneration) {
            issues.add("Model does not support text generation.")
        }

        return CompatibilityReport(
            isCompatible = issues.isEmpty(),
            issues = issues,
            warnings = warnings
        )
    }
}

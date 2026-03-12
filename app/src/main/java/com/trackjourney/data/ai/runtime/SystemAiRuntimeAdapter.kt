package com.trackjourney.data.ai.runtime

import android.content.Context
import android.content.pm.PackageManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemAiRuntimeAdapter @Inject constructor(
    private val context: Context
) : LocalModelRuntime {

    override val runtimeId: String = "system_aicore"
    override val displayName: String = "Android AICore (Gemini Nano)"

    override fun isAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.google.android.aicore", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getStatusMessage(): String {
        return if (isAvailable()) {
            val version = try {
                context.packageManager.getPackageInfo("com.google.android.aicore", 0).versionName
            } catch (e: Exception) {
                "unknown"
            }
            "AICore available (v$version)"
        } else {
            "AICore not available on this device"
        }
    }

    override suspend fun runPrompt(prompt: String): String {
        // Placeholder — actual AICore/Gemini Nano API integration pending Google's stable public API.
        // When available, this will use GenerativeAI from com.google.android.aicore.
        if (!isAvailable()) {
            throw UnsupportedOperationException("AICore is not available on this device")
        }
        return "{\"status\": \"system_ai_placeholder\", \"message\": \"AICore integration pending stable API\"}"
    }

    override fun supportsStructuredJson(): Boolean = true

    override fun supportsStreaming(): Boolean = false

    override fun release() {
        // No resources to release for system AI
    }
}

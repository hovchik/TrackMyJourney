package com.trackjourney.data.ai.provider

import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.AiPreferences
import javax.inject.Inject
import javax.inject.Singleton

data class ProviderSelection(
    val provider: AiAnalysisProvider,
    val mode: AiExecutionMode,
    val reason: String
)

@Singleton
class AiProviderSelector @Inject constructor(
    private val aiPreferences: AiPreferences,
    private val systemAiProvider: SystemAiProvider,
    private val customLocalModelProvider: CustomLocalModelProvider,
    private val cloudProvider: CloudProvider
) {
    suspend fun selectProvider(): ProviderSelection {
        val preferredMode = aiPreferences.getSelectedMode()
        return selectForMode(preferredMode)
    }

    fun selectForMode(mode: AiExecutionMode): ProviderSelection {
        return when (mode) {
            AiExecutionMode.AUTO -> selectAuto()
            AiExecutionMode.SYSTEM_LOCAL -> selectSystemLocal()
            AiExecutionMode.CUSTOM_LOCAL -> selectCustomLocal()
            AiExecutionMode.CLOUD -> selectCloud()
        }
    }

    private fun selectAuto(): ProviderSelection {
        // Priority: System AI -> Custom Local -> Cloud
        if (systemAiProvider.isAvailable()) {
            return ProviderSelection(systemAiProvider, AiExecutionMode.SYSTEM_LOCAL, "System AI is available")
        }
        if (customLocalModelProvider.isAvailable()) {
            return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "Local model is available")
        }
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "Falling back to cloud AI")
    }

    private fun selectSystemLocal(): ProviderSelection {
        if (systemAiProvider.isAvailable()) {
            return ProviderSelection(systemAiProvider, AiExecutionMode.SYSTEM_LOCAL, "System AI selected")
        }
        // Fallback chain: Custom Local -> Cloud
        if (customLocalModelProvider.isAvailable()) {
            return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "System AI unavailable, using local model")
        }
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "System AI unavailable, falling back to cloud")
    }

    private fun selectCustomLocal(): ProviderSelection {
        if (customLocalModelProvider.isAvailable()) {
            return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "Local model selected")
        }
        // Fallback: Cloud
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "Local model unavailable, falling back to cloud")
    }

    private fun selectCloud(): ProviderSelection {
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "Cloud AI selected")
    }
}

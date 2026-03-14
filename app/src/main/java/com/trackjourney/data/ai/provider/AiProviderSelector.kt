package com.trackjourney.data.ai.provider

import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.AiPreferences
import com.trackjourney.data.local.SettingsDataStore
import kotlinx.coroutines.flow.first
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
    private val cloudProvider: CloudProvider,
    private val settingsDataStore: SettingsDataStore
) {
    suspend fun selectProvider(): ProviderSelection {
        val preferredMode = aiPreferences.getSelectedMode()
        return selectForMode(preferredMode)
    }

    suspend fun selectForMode(mode: AiExecutionMode): ProviderSelection {
        val isPremium = settingsDataStore.subscriptionStatus.first().isActive
        return when (mode) {
            AiExecutionMode.AUTO -> selectAuto(isPremium)
            AiExecutionMode.SYSTEM_LOCAL -> selectSystemLocal(isPremium)
            AiExecutionMode.CUSTOM_LOCAL -> selectCustomLocal(isPremium)
            AiExecutionMode.CLOUD -> selectCloud()
        }
    }

    private fun selectAuto(isPremium: Boolean): ProviderSelection {
        // Priority: System AI -> Custom Local (premium) -> Cloud
        if (systemAiProvider.isAvailable()) {
            return ProviderSelection(systemAiProvider, AiExecutionMode.SYSTEM_LOCAL, "System AI is available")
        }
        if (isPremium && customLocalModelProvider.isAvailable()) {
            return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "Local model is available")
        }
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "Falling back to cloud AI")
    }

    private fun selectSystemLocal(isPremium: Boolean): ProviderSelection {
        if (systemAiProvider.isAvailable()) {
            return ProviderSelection(systemAiProvider, AiExecutionMode.SYSTEM_LOCAL, "System AI selected")
        }
        // Fallback chain: Custom Local (premium) -> Cloud
        if (isPremium && customLocalModelProvider.isAvailable()) {
            return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "System AI unavailable, using local model")
        }
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "System AI unavailable, falling back to cloud")
    }

    private fun selectCustomLocal(isPremium: Boolean): ProviderSelection {
        if (!isPremium) {
            return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "Local AI models require Premium subscription, falling back to cloud")
        }
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

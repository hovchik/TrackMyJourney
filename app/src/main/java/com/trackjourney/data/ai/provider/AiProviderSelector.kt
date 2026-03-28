package com.trackjourney.data.ai.provider

import android.util.Log
import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.AiPreferences
import com.trackjourney.data.local.SettingsDataStore
import com.trackjourney.data.model.AiMode
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
    private val customLocalModelProvider: CustomLocalModelProvider,
    private val cloudProvider: CloudProvider,
    private val settingsDataStore: SettingsDataStore
) {
    companion object {
        private const val TAG = "AiProviderSelector"
    }

    /**
     * Selects the AI provider based on the user's settings.
     * Checks SettingsDataStore (AiMode) as the primary source of truth,
     * since the Settings screen writes there. Falls back to AiPreferences.
     */
    suspend fun selectProvider(): ProviderSelection {
        // Primary: check SettingsDataStore (what the Settings screen writes to)
        val settingsMode = settingsDataStore.settings.first().aiMode
        val resolvedMode = when (settingsMode) {
            AiMode.CLOUD_AI -> AiExecutionMode.CLOUD
            AiMode.OFF -> AiExecutionMode.CUSTOM_LOCAL // OFF falls back to rule-based
            else -> AiExecutionMode.CUSTOM_LOCAL
        }
        Log.d(TAG, "Selecting provider: settingsAiMode=$settingsMode -> $resolvedMode")
        return selectForMode(resolvedMode)
    }

    suspend fun selectForMode(mode: AiExecutionMode): ProviderSelection {
        return when (mode) {
            AiExecutionMode.CLOUD -> selectCloud()
            else -> selectLocal()
        }
    }

    private fun selectLocal(): ProviderSelection {
        if (customLocalModelProvider.isConfigured()) {
            return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "Local model selected")
        }
        Log.w(TAG, "No local model installed, will use rule-based fallback")
        return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "No local model, using rule-based analysis")
    }

    private suspend fun selectCloud(): ProviderSelection {
        cloudProvider.ensureConfigLoaded()
        // Also load API key from SettingsDataStore as fallback
        if (!cloudProvider.isConfigured()) {
            val settings = settingsDataStore.settings.first()
            if (settings.cloudAiApiKey.isNotBlank()) {
                cloudProvider.setApiKey(settings.cloudAiApiKey)
                Log.d(TAG, "Loaded cloud API key from SettingsDataStore")
            }
        }
        val providerName = cloudProvider.getProviderType().label
        if (cloudProvider.isConfigured()) {
            return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "Cloud AI selected ($providerName)")
        }
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "Cloud AI not configured — set API key in Settings")
    }
}

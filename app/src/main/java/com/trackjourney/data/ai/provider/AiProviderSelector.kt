package com.trackjourney.data.ai.provider

import android.util.Log
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
    companion object {
        private const val TAG = "AiProviderSelector"
    }

    suspend fun selectProvider(): ProviderSelection {
        val preferredMode = aiPreferences.getSelectedMode()
        Log.d(TAG, "Selecting provider for mode: $preferredMode")
        return selectForMode(preferredMode)
    }

    suspend fun selectForMode(mode: AiExecutionMode): ProviderSelection {
        return when (mode) {
            AiExecutionMode.AUTO -> selectAuto()
            AiExecutionMode.SYSTEM_LOCAL -> selectSystemLocal()
            AiExecutionMode.CUSTOM_LOCAL -> selectCustomLocal()
            AiExecutionMode.CLOUD -> selectCloud()
        }
    }

    private fun selectAuto(): ProviderSelection {
        // Priority: System AI -> Custom Local Model -> Cloud
        if (systemAiProvider.isAvailable()) {
            return ProviderSelection(systemAiProvider, AiExecutionMode.SYSTEM_LOCAL, "System AI is available")
        }
        if (customLocalModelProvider.isConfigured()) {
            Log.d(TAG, "Auto mode: local model is configured, checking availability...")
            if (customLocalModelProvider.isAvailable()) {
                return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "Local model is available")
            }
            // Model is configured but not yet loaded — still select it (it will load on first use)
            return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "Local model configured, will load on use")
        }
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "Falling back to cloud AI")
    }

    private fun selectSystemLocal(): ProviderSelection {
        if (systemAiProvider.isAvailable()) {
            return ProviderSelection(systemAiProvider, AiExecutionMode.SYSTEM_LOCAL, "System AI selected")
        }
        if (customLocalModelProvider.isConfigured()) {
            return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "System AI unavailable, using local model")
        }
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "System AI unavailable, falling back to cloud")
    }

    private fun selectCustomLocal(): ProviderSelection {
        if (customLocalModelProvider.isConfigured()) {
            return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "Local model selected")
        }
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "No local model installed, falling back to cloud")
    }

    private fun selectCloud(): ProviderSelection {
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "Cloud AI selected")
    }
}

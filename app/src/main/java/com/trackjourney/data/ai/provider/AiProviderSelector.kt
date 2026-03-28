package com.trackjourney.data.ai.provider

import android.util.Log
import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.AiPreferences
import com.trackjourney.data.local.SettingsDataStore
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

    suspend fun selectProvider(): ProviderSelection {
        val preferredMode = aiPreferences.getSelectedMode()
        Log.d(TAG, "Selecting provider for mode: $preferredMode")
        return selectForMode(preferredMode)
    }

    suspend fun selectForMode(mode: AiExecutionMode): ProviderSelection {
        return when (mode) {
            AiExecutionMode.CLOUD -> selectCloud()
            // All other modes (AUTO, SYSTEM_LOCAL, CUSTOM_LOCAL) resolve to local model
            else -> selectLocal()
        }
    }

    private fun selectLocal(): ProviderSelection {
        if (customLocalModelProvider.isConfigured()) {
            return ProviderSelection(customLocalModelProvider, AiExecutionMode.CUSTOM_LOCAL, "Local model selected")
        }
        Log.w(TAG, "No local model installed, falling back to cloud")
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "No local model installed, falling back to cloud")
    }

    private fun selectCloud(): ProviderSelection {
        return ProviderSelection(cloudProvider, AiExecutionMode.CLOUD, "Cloud AI selected")
    }
}

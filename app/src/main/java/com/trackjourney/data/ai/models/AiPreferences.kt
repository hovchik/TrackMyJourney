package com.trackjourney.data.ai.models

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_preferences")

@Singleton
class AiPreferences @Inject constructor(
    private val context: Context
) {
    companion object {
        val SELECTED_AI_MODE = stringPreferencesKey("selected_ai_mode")
        val LOCAL_AI_SETUP_COMPLETED = booleanPreferencesKey("local_ai_setup_completed")
        val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
        val CLOUD_API_KEY = stringPreferencesKey("cloud_api_key")
        val CLOUD_PROVIDER_TYPE = stringPreferencesKey("cloud_provider_type")
    }

    fun observeSelectedMode(): Flow<AiExecutionMode> = context.aiDataStore.data.map { prefs ->
        try {
            AiExecutionMode.valueOf(prefs[SELECTED_AI_MODE] ?: AiExecutionMode.CUSTOM_LOCAL.name)
        } catch (e: Exception) {
            AiExecutionMode.CUSTOM_LOCAL
        }
    }

    suspend fun getSelectedMode(): AiExecutionMode {
        return observeSelectedMode().first()
    }

    suspend fun setSelectedMode(mode: AiExecutionMode) {
        context.aiDataStore.edit { it[SELECTED_AI_MODE] = mode.name }
    }

    fun observeSetupCompleted(): Flow<Boolean> = context.aiDataStore.data.map { prefs ->
        prefs[LOCAL_AI_SETUP_COMPLETED] ?: false
    }

    suspend fun setSetupCompleted(completed: Boolean) {
        context.aiDataStore.edit { it[LOCAL_AI_SETUP_COMPLETED] = completed }
    }

    fun observeActiveModelId(): Flow<String?> = context.aiDataStore.data.map { prefs ->
        prefs[ACTIVE_MODEL_ID]
    }

    suspend fun getActiveModelId(): String? {
        return observeActiveModelId().first()
    }

    suspend fun setActiveModelId(id: String?) {
        context.aiDataStore.edit { prefs ->
            if (id != null) {
                prefs[ACTIVE_MODEL_ID] = id
            } else {
                prefs.remove(ACTIVE_MODEL_ID)
            }
        }
    }

    fun observeCloudApiKey(): Flow<String?> = context.aiDataStore.data.map { prefs ->
        prefs[CLOUD_API_KEY]
    }

    suspend fun getCloudApiKey(): String? {
        return observeCloudApiKey().first()
    }

    suspend fun setCloudApiKey(key: String?) {
        context.aiDataStore.edit { prefs ->
            if (key != null) {
                prefs[CLOUD_API_KEY] = key
            } else {
                prefs.remove(CLOUD_API_KEY)
            }
        }
    }

    fun observeCloudProviderType(): Flow<String> = context.aiDataStore.data.map { prefs ->
        prefs[CLOUD_PROVIDER_TYPE] ?: CloudProviderType.CLAUDE.name
    }

    suspend fun getCloudProviderType(): String {
        return observeCloudProviderType().first()
    }

    suspend fun setCloudProviderType(type: String) {
        context.aiDataStore.edit { prefs ->
            prefs[CLOUD_PROVIDER_TYPE] = type
        }
    }
}

enum class CloudProviderType(val label: String, val description: String) {
    CLAUDE("Claude (Anthropic)", "Anthropic's Claude AI for intelligent analysis."),
    OPENAI("GPT (OpenAI)", "OpenAI's GPT models for analysis."),
    GEMINI("Gemini (Google)", "Google's Gemini AI for analysis."),
    DEEPSEEK("DeepSeek", "DeepSeek's AI models for analysis.")
}

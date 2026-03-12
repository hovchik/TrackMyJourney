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
    }

    fun observeSelectedMode(): Flow<AiExecutionMode> = context.aiDataStore.data.map { prefs ->
        try {
            AiExecutionMode.valueOf(prefs[SELECTED_AI_MODE] ?: AiExecutionMode.AUTO.name)
        } catch (e: Exception) {
            AiExecutionMode.AUTO
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
}

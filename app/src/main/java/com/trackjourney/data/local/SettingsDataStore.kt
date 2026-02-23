package com.trackjourney.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.trackjourney.data.model.ExportFormat
import com.trackjourney.data.model.TrackingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tracking_settings")

class SettingsDataStore(
    private val context: Context
) {
    companion object {
        val RECORD_INTERVAL_MS = longPreferencesKey("record_interval_ms")
        val MIN_DISTANCE_METERS = floatPreferencesKey("min_distance_meters")
        val AUTO_DETECT_ACTIVITY = booleanPreferencesKey("auto_detect_activity")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val EXPORT_FORMAT = stringPreferencesKey("export_format")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_WEIGHT_KG = floatPreferencesKey("user_weight_kg")
        val USER_HEIGHT_CM = floatPreferencesKey("user_height_cm")
    }

    val settings: Flow<TrackingSettings> = context.dataStore.data.map { prefs ->
        TrackingSettings(
            recordIntervalMs = prefs[RECORD_INTERVAL_MS] ?: 3000L,
            minDistanceMeters = prefs[MIN_DISTANCE_METERS] ?: 5f,
            autoDetectActivity = prefs[AUTO_DETECT_ACTIVITY] ?: true,
            keepScreenOn = prefs[KEEP_SCREEN_ON] ?: false,
            exportFormat = try {
                ExportFormat.valueOf(prefs[EXPORT_FORMAT] ?: "JSON")
            } catch (e: Exception) {
                ExportFormat.JSON
            },
            userName = prefs[USER_NAME] ?: "",
            userWeightKg = prefs[USER_WEIGHT_KG] ?: 70f,
            userHeightCm = prefs[USER_HEIGHT_CM] ?: 170f
        )
    }

    suspend fun updateRecordInterval(intervalMs: Long) {
        context.dataStore.edit { it[RECORD_INTERVAL_MS] = intervalMs }
    }

    suspend fun updateMinDistance(meters: Float) {
        context.dataStore.edit { it[MIN_DISTANCE_METERS] = meters }
    }

    suspend fun updateAutoDetectActivity(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_DETECT_ACTIVITY] = enabled }
    }

    suspend fun updateKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    suspend fun updateExportFormat(format: ExportFormat) {
        context.dataStore.edit { it[EXPORT_FORMAT] = format.name }
    }

    suspend fun updateUserName(name: String) {
        context.dataStore.edit { it[USER_NAME] = name }
    }

    suspend fun updateUserWeightKg(weight: Float) {
        context.dataStore.edit { it[USER_WEIGHT_KG] = weight }
    }

    suspend fun updateUserHeightCm(height: Float) {
        context.dataStore.edit { it[USER_HEIGHT_CM] = height }
    }

    suspend fun updateAll(settings: TrackingSettings) {
        context.dataStore.edit { prefs ->
            prefs[RECORD_INTERVAL_MS] = settings.recordIntervalMs
            prefs[MIN_DISTANCE_METERS] = settings.minDistanceMeters
            prefs[AUTO_DETECT_ACTIVITY] = settings.autoDetectActivity
            prefs[KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[EXPORT_FORMAT] = settings.exportFormat.name
            prefs[USER_NAME] = settings.userName
            prefs[USER_WEIGHT_KG] = settings.userWeightKg
            prefs[USER_HEIGHT_CM] = settings.userHeightCm
        }
    }
}

package com.trackjourney.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.trackjourney.data.model.ExportFormat
import com.trackjourney.data.model.TrackingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tracking_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    private val context: Context
) {
    companion object {
        val RECORD_INTERVAL_MS = longPreferencesKey("record_interval_ms")
        val MIN_DISTANCE_METERS = floatPreferencesKey("min_distance_meters")
        val ENABLE_HEART_RATE = booleanPreferencesKey("enable_heart_rate")
        val ENABLE_SPO2 = booleanPreferencesKey("enable_spo2")
        val AUTO_DETECT_ACTIVITY = booleanPreferencesKey("auto_detect_activity")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val EXPORT_FORMAT = stringPreferencesKey("export_format")
    }

    val settings: Flow<TrackingSettings> = context.dataStore.data.map { prefs ->
        TrackingSettings(
            recordIntervalMs = prefs[RECORD_INTERVAL_MS] ?: 3000L,
            minDistanceMeters = prefs[MIN_DISTANCE_METERS] ?: 5f,
            enableHeartRate = prefs[ENABLE_HEART_RATE] ?: true,
            enableSpO2 = prefs[ENABLE_SPO2] ?: true,
            autoDetectActivity = prefs[AUTO_DETECT_ACTIVITY] ?: true,
            keepScreenOn = prefs[KEEP_SCREEN_ON] ?: false,
            exportFormat = try {
                ExportFormat.valueOf(prefs[EXPORT_FORMAT] ?: "JSON")
            } catch (e: Exception) {
                ExportFormat.JSON
            }
        )
    }

    suspend fun updateRecordInterval(intervalMs: Long) {
        context.dataStore.edit { it[RECORD_INTERVAL_MS] = intervalMs }
    }

    suspend fun updateMinDistance(meters: Float) {
        context.dataStore.edit { it[MIN_DISTANCE_METERS] = meters }
    }

    suspend fun updateEnableHeartRate(enabled: Boolean) {
        context.dataStore.edit { it[ENABLE_HEART_RATE] = enabled }
    }

    suspend fun updateEnableSpO2(enabled: Boolean) {
        context.dataStore.edit { it[ENABLE_SPO2] = enabled }
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

    suspend fun updateAll(settings: TrackingSettings) {
        context.dataStore.edit { prefs ->
            prefs[RECORD_INTERVAL_MS] = settings.recordIntervalMs
            prefs[MIN_DISTANCE_METERS] = settings.minDistanceMeters
            prefs[ENABLE_HEART_RATE] = settings.enableHeartRate
            prefs[ENABLE_SPO2] = settings.enableSpO2
            prefs[AUTO_DETECT_ACTIVITY] = settings.autoDetectActivity
            prefs[KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[EXPORT_FORMAT] = settings.exportFormat.name
        }
    }
}

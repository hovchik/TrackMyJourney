package com.trackjourney.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.trackjourney.data.model.*
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
        val TRACKING_MODE = stringPreferencesKey("tracking_mode")
        val CAR_MODEL = stringPreferencesKey("car_model")
        val CAR_YEAR = intPreferencesKey("car_year")
        val ENGINE_SIZE = floatPreferencesKey("engine_size")
        val IS_ELECTRIC_CAR = booleanPreferencesKey("is_electric_car")
        val FUEL_TYPE = stringPreferencesKey("fuel_type")
        val FUEL_PRICE_PER_LITER = floatPreferencesKey("fuel_price_per_liter")
        val FUEL_CONSUMPTION = floatPreferencesKey("fuel_consumption")
        val BATTERY_CAPACITY_KWH = floatPreferencesKey("battery_capacity_kwh")
        val ELECTRIC_CONSUMPTION = floatPreferencesKey("electric_consumption")
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
            userHeightCm = prefs[USER_HEIGHT_CM] ?: 170f,
            trackingMode = try {
                TrackingMode.valueOf(prefs[TRACKING_MODE] ?: "HIGH_ACCURACY")
            } catch (e: Exception) { TrackingMode.HIGH_ACCURACY },
            carModel = prefs[CAR_MODEL] ?: "",
            carYear = prefs[CAR_YEAR] ?: 2024,
            engineSize = prefs[ENGINE_SIZE] ?: 0f,
            isElectricCar = prefs[IS_ELECTRIC_CAR] ?: false,
            fuelType = try {
                FuelType.valueOf(prefs[FUEL_TYPE] ?: "PETROL")
            } catch (e: Exception) { FuelType.PETROL },
            fuelPricePerLiter = prefs[FUEL_PRICE_PER_LITER] ?: 0f,
            fuelConsumption = prefs[FUEL_CONSUMPTION] ?: 8f,
            batteryCapacityKwh = prefs[BATTERY_CAPACITY_KWH] ?: 60f,
            electricConsumption = prefs[ELECTRIC_CONSUMPTION] ?: 15f
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

    suspend fun updateTrackingMode(mode: TrackingMode) {
        context.dataStore.edit { it[TRACKING_MODE] = mode.name }
    }

    suspend fun updateCarModel(model: String) {
        context.dataStore.edit { it[CAR_MODEL] = model }
    }

    suspend fun updateCarYear(year: Int) {
        context.dataStore.edit { it[CAR_YEAR] = year }
    }

    suspend fun updateEngineSize(size: Float) {
        context.dataStore.edit { it[ENGINE_SIZE] = size }
    }

    suspend fun updateIsElectricCar(isElectric: Boolean) {
        context.dataStore.edit { it[IS_ELECTRIC_CAR] = isElectric }
    }

    suspend fun updateFuelType(type: FuelType) {
        context.dataStore.edit { it[FUEL_TYPE] = type.name }
    }

    suspend fun updateFuelPricePerLiter(price: Float) {
        context.dataStore.edit { it[FUEL_PRICE_PER_LITER] = price }
    }

    suspend fun updateFuelConsumption(consumption: Float) {
        context.dataStore.edit { it[FUEL_CONSUMPTION] = consumption }
    }

    suspend fun updateBatteryCapacityKwh(capacity: Float) {
        context.dataStore.edit { it[BATTERY_CAPACITY_KWH] = capacity }
    }

    suspend fun updateElectricConsumption(consumption: Float) {
        context.dataStore.edit { it[ELECTRIC_CONSUMPTION] = consumption }
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
            prefs[TRACKING_MODE] = settings.trackingMode.name
            prefs[CAR_MODEL] = settings.carModel
            prefs[CAR_YEAR] = settings.carYear
            prefs[ENGINE_SIZE] = settings.engineSize
            prefs[IS_ELECTRIC_CAR] = settings.isElectricCar
            prefs[FUEL_TYPE] = settings.fuelType.name
            prefs[FUEL_PRICE_PER_LITER] = settings.fuelPricePerLiter
            prefs[FUEL_CONSUMPTION] = settings.fuelConsumption
            prefs[BATTERY_CAPACITY_KWH] = settings.batteryCapacityKwh
            prefs[ELECTRIC_CONSUMPTION] = settings.electricConsumption
        }
    }
}

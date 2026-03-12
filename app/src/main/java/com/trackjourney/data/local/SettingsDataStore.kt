package com.trackjourney.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.trackjourney.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tracking_settings")

class SettingsDataStore(
    private val context: Context
) {
    private val gson = Gson()

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
        val SELECTED_CAR_ID = stringPreferencesKey("selected_car_id")
        val CURRENCY = stringPreferencesKey("currency")
        val ACTIVITY_CONFIGS = stringPreferencesKey("activity_configs")
        val AI_MODE = stringPreferencesKey("ai_mode")
        val CLOUD_AI_PROVIDER = stringPreferencesKey("cloud_ai_provider")
        val CLOUD_AI_API_KEY = stringPreferencesKey("cloud_ai_api_key")
        val CLOUD_AI_ENDPOINT = stringPreferencesKey("cloud_ai_endpoint")
        val CLOUD_AI_MODEL = stringPreferencesKey("cloud_ai_model")
        val WEBHOOK_ENABLED = booleanPreferencesKey("webhook_enabled")
        val WEBHOOK_URL = stringPreferencesKey("webhook_url")
        val WEBHOOK_KEY = stringPreferencesKey("webhook_key")
        val WEBHOOK_INTERVAL_MS = longPreferencesKey("webhook_interval_ms")
        val SUBSCRIPTION_ACTIVE = booleanPreferencesKey("subscription_active")
        val SUBSCRIPTION_PLAN = stringPreferencesKey("subscription_plan")
        val SUBSCRIPTION_EXPIRY = longPreferencesKey("subscription_expiry")
        val SUBSCRIPTION_TOKEN = stringPreferencesKey("subscription_token")
        val FREE_TRIAL_START = longPreferencesKey("free_trial_start")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    private fun parseActivityConfigs(json: String?): List<ActivityConfig> {
        if (json.isNullOrBlank()) return ActivityConfig.defaults()
        return try {
            val type = object : TypeToken<List<ActivityConfig>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            ActivityConfig.defaults()
        }
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
            selectedCarId = prefs[SELECTED_CAR_ID],
            currency = prefs[CURRENCY] ?: "$",
            activityConfigs = parseActivityConfigs(prefs[ACTIVITY_CONFIGS]),
            aiMode = try {
                AiMode.valueOf(prefs[AI_MODE] ?: "RULE_BASED")
            } catch (e: Exception) { AiMode.RULE_BASED },
            cloudAiProvider = try {
                CloudAiProvider.valueOf(prefs[CLOUD_AI_PROVIDER] ?: "OPENAI")
            } catch (e: Exception) { CloudAiProvider.OPENAI },
            cloudAiApiKey = prefs[CLOUD_AI_API_KEY] ?: "",
            cloudAiEndpoint = prefs[CLOUD_AI_ENDPOINT] ?: "",
            cloudAiModel = prefs[CLOUD_AI_MODEL] ?: "",
            webhookEnabled = prefs[WEBHOOK_ENABLED] ?: false,
            webhookUrl = prefs[WEBHOOK_URL] ?: "https://pathwise.art",
            webhookKey = prefs[WEBHOOK_KEY] ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16),
            webhookIntervalMs = prefs[WEBHOOK_INTERVAL_MS] ?: 10000L
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

    suspend fun updateCurrency(currency: String) {
        context.dataStore.edit { it[CURRENCY] = currency }
    }

    suspend fun updateSelectedCarId(carId: String?) {
        context.dataStore.edit { prefs ->
            if (carId != null) {
                prefs[SELECTED_CAR_ID] = carId
            } else {
                prefs.remove(SELECTED_CAR_ID)
            }
        }
    }

    suspend fun updateActivityConfigs(configs: List<ActivityConfig>) {
        context.dataStore.edit { it[ACTIVITY_CONFIGS] = gson.toJson(configs) }
    }

    suspend fun updateAiMode(mode: AiMode) {
        context.dataStore.edit { it[AI_MODE] = mode.name }
    }

    suspend fun updateCloudAiProvider(provider: CloudAiProvider) {
        context.dataStore.edit { it[CLOUD_AI_PROVIDER] = provider.name }
    }

    suspend fun updateCloudAiApiKey(apiKey: String) {
        context.dataStore.edit { it[CLOUD_AI_API_KEY] = apiKey }
    }

    suspend fun updateCloudAiEndpoint(endpoint: String) {
        context.dataStore.edit { it[CLOUD_AI_ENDPOINT] = endpoint }
    }

    suspend fun updateCloudAiModel(model: String) {
        context.dataStore.edit { it[CLOUD_AI_MODEL] = model }
    }

    suspend fun updateWebhookEnabled(enabled: Boolean) {
        context.dataStore.edit { it[WEBHOOK_ENABLED] = enabled }
    }

    suspend fun updateWebhookUrl(url: String) {
        context.dataStore.edit { it[WEBHOOK_URL] = url }
    }

    suspend fun updateWebhookKey(key: String) {
        context.dataStore.edit { it[WEBHOOK_KEY] = key }
    }

    suspend fun updateWebhookIntervalMs(intervalMs: Long) {
        context.dataStore.edit { it[WEBHOOK_INTERVAL_MS] = intervalMs }
    }

    // ─── Subscription ──────────────────────────────────

    val subscriptionStatus: Flow<SubscriptionStatus> = context.dataStore.data.map { prefs ->
        SubscriptionStatus(
            isSubscribed = prefs[SUBSCRIPTION_ACTIVE] ?: false,
            plan = try {
                prefs[SUBSCRIPTION_PLAN]?.let { SubscriptionPlan.valueOf(it) }
            } catch (_: Exception) { null },
            expiryTime = prefs[SUBSCRIPTION_EXPIRY] ?: 0L,
            purchaseToken = prefs[SUBSCRIPTION_TOKEN] ?: "",
            freeTrialStartTime = prefs[FREE_TRIAL_START] ?: 0L
        )
    }

    suspend fun updateSubscriptionStatus(status: SubscriptionStatus) {
        context.dataStore.edit { prefs ->
            prefs[SUBSCRIPTION_ACTIVE] = status.isSubscribed
            if (status.plan != null) {
                prefs[SUBSCRIPTION_PLAN] = status.plan.name
            } else {
                prefs.remove(SUBSCRIPTION_PLAN)
            }
            prefs[SUBSCRIPTION_EXPIRY] = status.expiryTime
            prefs[SUBSCRIPTION_TOKEN] = status.purchaseToken
            prefs[FREE_TRIAL_START] = status.freeTrialStartTime
        }
    }

    suspend fun startFreeTrial() {
        context.dataStore.edit { prefs ->
            if ((prefs[FREE_TRIAL_START] ?: 0L) == 0L) {
                prefs[FREE_TRIAL_START] = System.currentTimeMillis()
            }
        }
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ONBOARDING_COMPLETED] ?: false
    }

    suspend fun completeOnboarding() {
        context.dataStore.edit { it[ONBOARDING_COMPLETED] = true }
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
            if (settings.selectedCarId != null) {
                prefs[SELECTED_CAR_ID] = settings.selectedCarId
            } else {
                prefs.remove(SELECTED_CAR_ID)
            }
            prefs[CURRENCY] = settings.currency
            prefs[ACTIVITY_CONFIGS] = gson.toJson(settings.activityConfigs)
            prefs[AI_MODE] = settings.aiMode.name
            prefs[CLOUD_AI_PROVIDER] = settings.cloudAiProvider.name
            prefs[CLOUD_AI_API_KEY] = settings.cloudAiApiKey
            prefs[CLOUD_AI_ENDPOINT] = settings.cloudAiEndpoint
            prefs[CLOUD_AI_MODEL] = settings.cloudAiModel
            prefs[WEBHOOK_ENABLED] = settings.webhookEnabled
            prefs[WEBHOOK_URL] = settings.webhookUrl
            prefs[WEBHOOK_KEY] = settings.webhookKey
            prefs[WEBHOOK_INTERVAL_MS] = settings.webhookIntervalMs
        }
    }
}

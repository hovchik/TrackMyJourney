package com.trackjourney.di

import android.content.Context
import androidx.room.Room
import com.trackjourney.data.ai.LocalAiEngine
import com.trackjourney.data.ai.models.*
import com.trackjourney.data.ai.runtime.*
import com.trackjourney.data.ai.provider.*
import com.trackjourney.data.billing.BillingManager
import com.trackjourney.data.bluetooth.WearableManager
import com.trackjourney.data.local.*
import com.trackjourney.data.location.BatteryMonitor
import com.trackjourney.data.location.GpsSatelliteTracker
import com.trackjourney.data.location.LocationTracker
import com.trackjourney.data.location.MotionSensorManager
import com.trackjourney.data.location.SmartIntervalManager
import com.trackjourney.data.repository.TrackRepository
import com.trackjourney.data.webhook.WebhookSender
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TrackDatabase {
        return Room.databaseBuilder(
            context,
            TrackDatabase::class.java,
            "track_my_journey.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideTrackDao(db: TrackDatabase) = db.trackDao()
    @Provides fun provideTrackPointDao(db: TrackDatabase) = db.trackPointDao()
    @Provides fun provideHealthDataDao(db: TrackDatabase) = db.healthDataDao()
    @Provides fun provideAiAnalysisDao(db: TrackDatabase) = db.aiAnalysisDao()
    @Provides fun provideCarProfileDao(db: TrackDatabase) = db.carProfileDao()
    @Provides fun provideLocalAiModelDao(db: TrackDatabase) = db.localAiModelDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context) = SettingsDataStore(context)

    // ── AI Model Management ────────────────────────────────

    @Provides
    @Singleton
    fun provideAiPreferences(@ApplicationContext context: Context) = AiPreferences(context)

    @Provides
    @Singleton
    fun provideDeviceAiCapabilityDetector(@ApplicationContext context: Context) = DeviceAiCapabilityDetector(context)

    @Provides
    @Singleton
    fun provideModelCompatibilityValidator(detector: DeviceAiCapabilityDetector) = ModelCompatibilityValidator(detector)

    @Provides
    @Singleton
    fun provideLocalModelManager(dao: LocalAiModelDao, aiPreferences: AiPreferences) = LocalModelManager(dao, aiPreferences)

    @Provides
    @Singleton
    fun provideModelInstaller(
        @ApplicationContext context: Context,
        localModelManager: LocalModelManager,
        compatibilityValidator: ModelCompatibilityValidator
    ) = ModelInstaller(context, localModelManager, compatibilityValidator)

    // ── Runtime Adapters ───────────────────────────────────

    @Provides
    @Singleton
    fun provideSystemAiRuntimeAdapter(@ApplicationContext context: Context) = SystemAiRuntimeAdapter(context)

    @Provides
    @Singleton
    fun provideMediaPipeLlmRuntimeAdapter(@ApplicationContext context: Context) = MediaPipeLlmRuntimeAdapter(context)

    @Provides
    @Singleton
    fun provideLiteRtRuntimeAdapter(@ApplicationContext context: Context) = LiteRtRuntimeAdapter(context)

    // ── AI Providers ───────────────────────────────────────

    @Provides
    @Singleton
    fun provideCloudProvider() = CloudProvider()

    @Provides
    @Singleton
    fun provideSystemAiProvider(systemRuntime: SystemAiRuntimeAdapter) = SystemAiProvider(systemRuntime)

    @Provides
    @Singleton
    fun provideCustomLocalModelProvider(
        modelManager: LocalModelManager,
        mediaPipeRuntime: MediaPipeLlmRuntimeAdapter,
        liteRtRuntime: LiteRtRuntimeAdapter
    ) = CustomLocalModelProvider(modelManager, mediaPipeRuntime, liteRtRuntime)

    @Provides
    @Singleton
    fun provideAiProviderSelector(
        aiPreferences: AiPreferences,
        systemAiProvider: SystemAiProvider,
        customLocalModelProvider: CustomLocalModelProvider,
        cloudProvider: CloudProvider,
        settingsDataStore: SettingsDataStore
    ) = AiProviderSelector(aiPreferences, systemAiProvider, customLocalModelProvider, cloudProvider, settingsDataStore)

    @Provides
    @Singleton
    fun provideLocalAiBenchmarkRunner(
        @ApplicationContext context: Context,
        modelManager: LocalModelManager,
        mediaPipeRuntime: MediaPipeLlmRuntimeAdapter,
        liteRtRuntime: LiteRtRuntimeAdapter
    ) = LocalAiBenchmarkRunner(context, modelManager, mediaPipeRuntime, liteRtRuntime)

    @Provides
    @Singleton
    fun provideLocationTracker(@ApplicationContext context: Context) = LocationTracker(context)

    @Provides
    @Singleton
    fun provideGpsSatelliteTracker(@ApplicationContext context: Context) = GpsSatelliteTracker(context)

    @Provides
    @Singleton
    fun provideWearableManager(@ApplicationContext context: Context) = WearableManager(context)

    @Provides
    @Singleton
    fun provideLocalAiEngine(@ApplicationContext context: Context) = LocalAiEngine(context)

    @Provides
    @Singleton
    fun provideMotionSensorManager(@ApplicationContext context: Context) = MotionSensorManager(context)

    @Provides
    @Singleton
    fun provideBatteryMonitor(@ApplicationContext context: Context) = BatteryMonitor(context)

    @Provides
    @Singleton
    fun provideSmartIntervalManager(
        batteryMonitor: BatteryMonitor,
        motionSensorManager: MotionSensorManager
    ) = SmartIntervalManager(batteryMonitor, motionSensorManager)

    @Provides
    @Singleton
    fun provideWebhookSender(@ApplicationContext context: Context) = WebhookSender(context)

    @Provides
    @Singleton
    fun provideBillingManager(@ApplicationContext context: Context) = BillingManager(context)

    @Provides
    @Singleton
    fun provideTrackRepository(
        @ApplicationContext context: Context,
        trackDao: TrackDao,
        trackPointDao: TrackPointDao,
        healthDataDao: HealthDataDao,
        aiAnalysisDao: AiAnalysisDao,
        carProfileDao: CarProfileDao,
        locationTracker: LocationTracker,
        aiEngine: LocalAiEngine,
        aiProviderSelector: AiProviderSelector,
        settingsDataStore: SettingsDataStore,
        gpsSatelliteTracker: GpsSatelliteTracker,
        motionSensorManager: MotionSensorManager
    ) = TrackRepository(
        context, trackDao, trackPointDao, healthDataDao,
        aiAnalysisDao, carProfileDao, locationTracker, aiEngine, aiProviderSelector,
        settingsDataStore, gpsSatelliteTracker, motionSensorManager
    )
}

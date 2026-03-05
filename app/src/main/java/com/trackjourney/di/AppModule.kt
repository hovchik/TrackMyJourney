package com.trackjourney.di

import android.content.Context
import androidx.room.Room
import com.trackjourney.data.ai.LocalAiEngine
import com.trackjourney.data.bluetooth.WearableManager
import com.trackjourney.data.local.*
import com.trackjourney.data.location.BatteryMonitor
import com.trackjourney.data.location.GpsSatelliteTracker
import com.trackjourney.data.location.LocationTracker
import com.trackjourney.data.location.MotionSensorManager
import com.trackjourney.data.location.SmartIntervalManager
import com.trackjourney.data.repository.DiseaseRepository
import com.trackjourney.data.repository.TrackRepository
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
    @Provides fun providePersonDao(db: TrackDatabase) = db.personDao()
    @Provides fun provideDiseaseGroupDao(db: TrackDatabase) = db.diseaseGroupDao()
    @Provides fun provideDiseaseDao(db: TrackDatabase) = db.diseaseDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context) = SettingsDataStore(context)

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
    fun provideSmartIntervalManager(batteryMonitor: BatteryMonitor) = SmartIntervalManager(batteryMonitor)

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
        settingsDataStore: SettingsDataStore,
        gpsSatelliteTracker: GpsSatelliteTracker,
        motionSensorManager: MotionSensorManager
    ) = TrackRepository(
        context, trackDao, trackPointDao, healthDataDao,
        aiAnalysisDao, carProfileDao, locationTracker, aiEngine, settingsDataStore,
        gpsSatelliteTracker, motionSensorManager
    )
}

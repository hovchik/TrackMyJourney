package com.trackjourney

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class TrackMyJourneyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configure osmdroid
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir
        }
    }
}

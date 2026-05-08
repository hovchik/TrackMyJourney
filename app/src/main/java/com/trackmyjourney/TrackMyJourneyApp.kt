package com.trackmyjourney

import android.app.Application
import com.trackmyjourney.data.location.AutoTrackDetector
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class TrackMyJourneyApp : Application() {

    @Inject lateinit var autoTrackDetector: AutoTrackDetector

    override fun onCreate() {
        super.onCreate()

        // Configure osmdroid — load defaults from shared prefs, then set tile cache
        val prefs = getSharedPreferences("osmdroid", MODE_PRIVATE)
        Configuration.getInstance().load(this, prefs)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        autoTrackDetector.attach()
    }
}

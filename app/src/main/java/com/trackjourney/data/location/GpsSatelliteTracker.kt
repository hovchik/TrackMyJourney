package com.trackjourney.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SatelliteInfo(
    val totalVisible: Int = 0,
    val usedInFix: Int = 0
)

class GpsSatelliteTracker(
    private val context: Context
) {
    companion object {
        private const val TAG = "GpsSatelliteTracker"
        // Throttle state updates to avoid excessive recomposition
        private const val MIN_UPDATE_INTERVAL_MS = 2000L
    }

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _satelliteInfo = MutableStateFlow(SatelliteInfo())
    val satelliteInfo: StateFlow<SatelliteInfo> = _satelliteInfo.asStateFlow()

    private var gnssCallback: GnssStatus.Callback? = null
    private var lastUpdateTime = 0L

    @SuppressLint("MissingPermission")
    fun startMonitoring() {
        if (locationManager == null) return
        stopMonitoring()

        gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val now = System.currentTimeMillis()
                // Throttle updates to save CPU
                if (now - lastUpdateTime < MIN_UPDATE_INTERVAL_MS) return
                lastUpdateTime = now

                val total = status.satelliteCount
                var used = 0
                for (i in 0 until total) {
                    if (status.usedInFix(i)) {
                        used++
                    }
                }
                val newInfo = SatelliteInfo(totalVisible = total, usedInFix = used)
                // Only emit if values actually changed
                if (_satelliteInfo.value != newInfo) {
                    _satelliteInfo.value = newInfo
                }
            }
        }

        try {
            locationManager.registerGnssStatusCallback(gnssCallback!!, null)
            Log.i(TAG, "GNSS status monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register GNSS callback: ${e.message}")
        }
    }

    fun stopMonitoring() {
        gnssCallback?.let {
            try {
                locationManager?.unregisterGnssStatusCallback(it)
            } catch (_: Exception) { }
        }
        gnssCallback = null
        _satelliteInfo.value = SatelliteInfo()
        lastUpdateTime = 0L
    }
}

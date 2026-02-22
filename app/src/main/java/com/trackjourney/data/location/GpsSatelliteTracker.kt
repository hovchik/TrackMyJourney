package com.trackjourney.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

data class SatelliteInfo(
    val totalVisible: Int = 0,
    val usedInFix: Int = 0
)

class GpsSatelliteTracker(
    private val context: Context
) {
    companion object {
        private const val TAG = "GpsSatelliteTracker"
    }

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _satelliteInfo = MutableStateFlow(SatelliteInfo())
    val satelliteInfo: StateFlow<SatelliteInfo> = _satelliteInfo.asStateFlow()

    private var gnssCallback: GnssStatus.Callback? = null

    @SuppressLint("MissingPermission")
    fun startMonitoring() {
        if (locationManager == null) return
        stopMonitoring()

        gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val total = status.satelliteCount
                var used = 0
                for (i in 0 until total) {
                    if (status.usedInFix(i)) {
                        used++
                    }
                }
                _satelliteInfo.value = SatelliteInfo(
                    totalVisible = total,
                    usedInFix = used
                )
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
            locationManager?.unregisterGnssStatusCallback(it)
        }
        gnssCallback = null
        _satelliteInfo.value = SatelliteInfo()
    }
}

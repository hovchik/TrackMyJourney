package com.trackmyjourney.data.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BatteryMonitor(private val context: Context) {

    companion object {
        private const val TAG = "BatteryMonitor"
    }

    data class BatteryState(
        val isCharging: Boolean = false,
        val level: Int = -1
    )

    private val _batteryState = MutableStateFlow(BatteryState())
    val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateFromIntent(intent)
        }
    }

    private var isRegistered = false

    fun startMonitoring() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val stickyIntent = context.registerReceiver(receiver, filter)
        stickyIntent?.let { updateFromIntent(it) }
        isRegistered = true
        Log.i(TAG, "Battery monitoring started")
    }

    fun stopMonitoring() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receiver: ${e.message}")
        }
        isRegistered = false
        Log.i(TAG, "Battery monitoring stopped")
    }

    private fun updateFromIntent(intent: Intent) {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level.toLong() * 100 / scale).toInt() else -1

        _batteryState.value = BatteryState(isCharging = isCharging, level = percent)
    }
}

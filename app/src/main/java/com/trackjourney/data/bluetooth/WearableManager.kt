package com.trackjourney.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.trackjourney.data.model.WearableType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.util.UUID
// ─────────────────────────────────────────────────────────
//  STANDARD BLE GATT SERVICE / CHARACTERISTIC UUIDs
// ─────────────────────────────────────────────────────────

object BleUuids {
    // Heart Rate Service (0x180D)
    val HEART_RATE_SERVICE: UUID       = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT: UUID   = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    // Pulse Oximeter Service (0x1822) — SpO2
    val PULSE_OX_SERVICE: UUID         = UUID.fromString("00001822-0000-1000-8000-00805f9b34fb")
    val PULSE_OX_MEASUREMENT: UUID     = UUID.fromString("00002a5e-0000-1000-8000-00805f9b34fb")
    val PLX_CONTINUOUS: UUID           = UUID.fromString("00002a5f-0000-1000-8000-00805f9b34fb")

    // Client Characteristic Configuration Descriptor
    val CCCD: UUID                     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Device Information Service
    val DEVICE_INFO_SERVICE: UUID      = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER_NAME: UUID        = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
}

// ─────────────────────────────────────────────────────────
//  DATA CLASSES
// ─────────────────────────────────────────────────────────

data class WearableDevice(
    val name: String,
    val address: String,
    val type: WearableType,
    val rssi: Int = 0
)

data class WearableReading(
    val heartRate: Int? = null,
    val spO2: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceName: String = "",
    val deviceType: WearableType = WearableType.UNKNOWN
)

sealed class WearableConnectionState {
    data object Disconnected : WearableConnectionState()
    data object Scanning : WearableConnectionState()
    data object Connecting : WearableConnectionState()
    data class Connected(val device: WearableDevice) : WearableConnectionState()
    data class Error(val message: String) : WearableConnectionState()
}

// ─────────────────────────────────────────────────────────
//  BLE WEARABLE MANAGER
// ─────────────────────────────────────────────────────────

class WearableManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "WearableManager"
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var currentDevice: WearableDevice? = null

    private val _connectionState = MutableStateFlow<WearableConnectionState>(
        WearableConnectionState.Disconnected
    )
    val connectionState: StateFlow<WearableConnectionState> = _connectionState.asStateFlow()

    private val _readings = MutableSharedFlow<WearableReading>(replay = 1)
    val readings: SharedFlow<WearableReading> = _readings.asSharedFlow()

    private val _latestReading = MutableStateFlow<WearableReading?>(null)
    val latestReading: StateFlow<WearableReading?> = _latestReading.asStateFlow()

    val isBluetoothAvailable: Boolean
        get() = bluetoothAdapter != null

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    // ─── SCANNING ────────────────────────────────────────

    /**
     * Scan for BLE devices that expose Heart Rate or SpO2 services.
     * Both Garmin and Samsung Galaxy watches advertise standard BLE HR service.
     */
    @SuppressLint("MissingPermission")
    fun scanForDevices(): Flow<WearableDevice> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanner not available"))
            return@callbackFlow
        }

        _connectionState.value = WearableConnectionState.Scanning

        val scanFilters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleUuids.HEART_RATE_SERVICE))
                .build()
        )

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: "Unknown Device"
                val wearableType = detectWearableType(name)

                trySend(
                    WearableDevice(
                        name = name,
                        address = device.address,
                        type = wearableType,
                        rssi = result.rssi
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
                _connectionState.value = WearableConnectionState.Error("Scan failed: $errorCode")
            }
        }

        scanner.startScan(scanFilters, scanSettings, scanCallback)

        awaitClose {
            scanner.stopScan(scanCallback)
            if (_connectionState.value is WearableConnectionState.Scanning) {
                _connectionState.value = WearableConnectionState.Disconnected
            }
        }
    }

    // ─── CONNECTION ──────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: WearableDevice) {
        val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address) ?: return
        currentDevice = device
        _connectionState.value = WearableConnectionState.Connecting

        bluetoothGatt = bluetoothDevice.connectGatt(
            context,
            false, // autoConnect
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
        }
        bluetoothGatt = null
        currentDevice = null
        _connectionState.value = WearableConnectionState.Disconnected
        _latestReading.value = null
    }

    // ─── GATT CALLBACK ──────────────────────────────────

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to ${currentDevice?.name}")
                    _connectionState.value = WearableConnectionState.Connected(currentDevice!!)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from ${currentDevice?.name}")
                    _connectionState.value = WearableConnectionState.Disconnected
                    gatt.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = WearableConnectionState.Error("Service discovery failed")
                return
            }

            Log.i(TAG, "Services discovered: ${gatt.services.map { it.uuid }}")

            // Subscribe to Heart Rate Measurement notifications
            gatt.getService(BleUuids.HEART_RATE_SERVICE)?.let { service ->
                service.getCharacteristic(BleUuids.HEART_RATE_MEASUREMENT)?.let { char ->
                    enableNotifications(gatt, char)
                }
            }

            // Subscribe to SpO2 (Pulse Oximeter) notifications
            gatt.getService(BleUuids.PULSE_OX_SERVICE)?.let { service ->
                // Try continuous first, then spot measurement
                val char = service.getCharacteristic(BleUuids.PLX_CONTINUOUS)
                    ?: service.getCharacteristic(BleUuids.PULSE_OX_MEASUREMENT)
                char?.let { enableNotifications(gatt, it) }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                BleUuids.HEART_RATE_MEASUREMENT -> {
                    val heartRate = parseHeartRate(value)
                    updateReading(heartRate = heartRate)
                }
                BleUuids.PLX_CONTINUOUS, BleUuids.PULSE_OX_MEASUREMENT -> {
                    val spO2 = parseSpO2(value)
                    updateReading(spO2 = spO2)
                }
            }
        }

        // Compat override for API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                BleUuids.HEART_RATE_MEASUREMENT -> {
                    val heartRate = parseHeartRate(value)
                    updateReading(heartRate = heartRate)
                }
                BleUuids.PLX_CONTINUOUS, BleUuids.PULSE_OX_MEASUREMENT -> {
                    val spO2 = parseSpO2(value)
                    updateReading(spO2 = spO2)
                }
            }
        }
    }

    // ─── BLE HELPERS ─────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)

        characteristic.getDescriptor(BleUuids.CCCD)?.let { descriptor ->
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    /**
     * Parse Heart Rate per Bluetooth SIG Heart Rate Measurement spec.
     * Bit 0 of flags indicates HR format: 0 = UINT8, 1 = UINT16.
     */
    private fun parseHeartRate(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val flags = data[0].toInt()
        return if (flags and 0x01 == 0) {
            // UINT8 format
            data[1].toInt() and 0xFF
        } else {
            // UINT16 format
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        }
    }

    /**
     * Parse SpO2 from PLX Continuous / Spot-check measurement.
     * SpO2 is a SFLOAT (16-bit) at bytes 1-2.
     */
    private fun parseSpO2(data: ByteArray): Int {
        if (data.size < 3) return 0
        // SFLOAT: 4-bit exponent + 12-bit mantissa
        val raw = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        val mantissa = raw and 0x0FFF
        val exponent = (raw shr 12) and 0x0F
        val exp = if (exponent > 7) exponent - 16 else exponent
        return (mantissa * Math.pow(10.0, exp.toDouble())).toInt()
    }

    private fun updateReading(heartRate: Int? = null, spO2: Int? = null) {
        val current = _latestReading.value ?: WearableReading(
            deviceName = currentDevice?.name ?: "",
            deviceType = currentDevice?.type ?: WearableType.UNKNOWN
        )

        val updated = current.copy(
            heartRate = heartRate ?: current.heartRate,
            spO2 = spO2 ?: current.spO2,
            timestamp = System.currentTimeMillis()
        )

        _latestReading.value = updated
        _readings.tryEmit(updated)
    }

    private fun detectWearableType(name: String): WearableType {
        val lower = name.lowercase()
        return when {
            lower.contains("garmin")  -> WearableType.GARMIN
            lower.contains("galaxy")
                || lower.contains("samsung")
                || lower.contains("sm-r")
                || lower.contains("gear")  -> WearableType.SAMSUNG
            else                       -> WearableType.GENERIC_BLE
        }
    }
}

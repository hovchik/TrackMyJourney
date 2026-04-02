package com.trackjourney.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
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

    // Battery Service (0x180F)
    val BATTERY_SERVICE: UUID          = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID            = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // Running Speed and Cadence Service (0x1814)
    val RSC_SERVICE: UUID              = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
    val RSC_MEASUREMENT: UUID          = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")

    // Cycling Speed and Cadence Service (0x1816)
    val CSC_SERVICE: UUID              = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
    val CSC_MEASUREMENT: UUID          = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")

    // Device Information Service (0x180A)
    val DEVICE_INFO_SERVICE: UUID      = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER_NAME: UUID        = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val MODEL_NUMBER: UUID             = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID        = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    // Pulse Oximeter Service (0x1822) — SpO2
    val PLX_SERVICE: UUID              = UUID.fromString("00001822-0000-1000-8000-00805f9b34fb")
    val PLX_CONTINUOUS: UUID           = UUID.fromString("00002a5f-0000-1000-8000-00805f9b34fb")
    val PLX_SPOT_CHECK: UUID           = UUID.fromString("00002a5e-0000-1000-8000-00805f9b34fb")

    // Health Thermometer Service (0x1809) — Body temperature
    val HTS_SERVICE: UUID              = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb")
    val TEMPERATURE_MEASUREMENT: UUID  = UUID.fromString("00002a1c-0000-1000-8000-00805f9b34fb")

    // Client Characteristic Configuration Descriptor
    val CCCD: UUID                     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
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
    val rrIntervals: List<Int> = emptyList(),     // beat-to-beat intervals in ms
    val energyExpended: Int? = null,              // cumulative kJ
    val sensorContact: Boolean? = null,           // wrist contact detected
    val batteryLevel: Int? = null,                // 0-100%
    val cadence: Int? = null,                     // steps/min or rpm
    val spO2: Int? = null,                        // blood oxygen saturation 0-100%
    val temperatureC: Float? = null,              // body temperature in °C
    val timestamp: Long = System.currentTimeMillis(),
    val deviceName: String = "",
    val deviceType: WearableType = WearableType.UNKNOWN,
    val manufacturerName: String? = null,
    val modelNumber: String? = null
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

    // Queue for sequential GATT operations (BLE only handles one at a time)
    private val pendingGattOps = ArrayDeque<() -> Unit>()
    private var gattBusy = false

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
     * Scan for BLE devices that expose Heart Rate service.
     * Garmin Fenix watches advertise standard BLE HR when
     * "Broadcast Heart Rate" is enabled in watch settings.
     */
    @SuppressLint("MissingPermission")
    fun scanForDevices(): Flow<WearableDevice> = callbackFlow {
        // Check BLUETOOTH_SCAN permission at runtime (required on Android 12+)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _connectionState.value = WearableConnectionState.Error("Bluetooth scan permission not granted")
            close()
            return@callbackFlow
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = WearableConnectionState.Error("Bluetooth LE not available")
            close()
            return@callbackFlow
        }

        _connectionState.value = WearableConnectionState.Scanning

        // Scan for any device advertising a standard fitness BLE service.
        // Different brands advertise different services — Polar/Garmin/Wahoo
        // typically advertise HR, while cycling sensors may only advertise CSC,
        // and some wearables expose SpO2 (PLX) or RSC.
        val scanFilters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleUuids.HEART_RATE_SERVICE))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleUuids.RSC_SERVICE))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleUuids.CSC_SERVICE))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleUuids.PLX_SERVICE))
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
        pendingGattOps.clear()
        gattBusy = false

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
        pendingGattOps.clear()
        gattBusy = false
        _connectionState.value = WearableConnectionState.Disconnected
        _latestReading.value = null
    }

    // ─── GATT OPERATION QUEUE ────────────────────────────

    private fun enqueueGattOp(op: () -> Unit) {
        pendingGattOps.addLast(op)
        processNextGattOp()
    }

    private fun processNextGattOp() {
        if (gattBusy || pendingGattOps.isEmpty()) return
        gattBusy = true
        pendingGattOps.removeFirst().invoke()
    }

    private fun onGattOpComplete() {
        gattBusy = false
        processNextGattOp()
    }

    // ─── GATT CALLBACK ──────────────────────────────────

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val device = currentDevice ?: run {
                        Log.w(TAG, "STATE_CONNECTED but currentDevice is null — closing GATT")
                        gatt.close()
                        return
                    }
                    Log.i(TAG, "Connected to ${device.name}")
                    _connectionState.value = WearableConnectionState.Connected(device)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from ${currentDevice?.name}")
                    _connectionState.value = WearableConnectionState.Disconnected
                    gatt.close()
                    bluetoothGatt = null
                    pendingGattOps.clear()
                    gattBusy = false
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = WearableConnectionState.Error("Service discovery failed")
                return
            }

            Log.i(TAG, "Services discovered: ${gatt.services.map { it.uuid }}")

            // 1. Subscribe to Heart Rate Measurement notifications
            gatt.getService(BleUuids.HEART_RATE_SERVICE)?.let { service ->
                service.getCharacteristic(BleUuids.HEART_RATE_MEASUREMENT)?.let { char ->
                    enqueueGattOp { enableNotifications(gatt, char) }
                }
            }

            // 2. Subscribe to Battery Level notifications / read once
            gatt.getService(BleUuids.BATTERY_SERVICE)?.let { service ->
                service.getCharacteristic(BleUuids.BATTERY_LEVEL)?.let { char ->
                    enqueueGattOp { enableNotifications(gatt, char) }
                    enqueueGattOp { gatt.readCharacteristic(char) }
                }
            }

            // 3. Subscribe to Running Speed & Cadence
            gatt.getService(BleUuids.RSC_SERVICE)?.let { service ->
                service.getCharacteristic(BleUuids.RSC_MEASUREMENT)?.let { char ->
                    enqueueGattOp { enableNotifications(gatt, char) }
                }
            }

            // 4. Subscribe to Cycling Speed & Cadence
            gatt.getService(BleUuids.CSC_SERVICE)?.let { service ->
                service.getCharacteristic(BleUuids.CSC_MEASUREMENT)?.let { char ->
                    enqueueGattOp { enableNotifications(gatt, char) }
                }
            }

            // 5. Subscribe to Pulse Oximeter (SpO2)
            gatt.getService(BleUuids.PLX_SERVICE)?.let { service ->
                // Prefer continuous measurement, fall back to spot-check
                val char = service.getCharacteristic(BleUuids.PLX_CONTINUOUS)
                    ?: service.getCharacteristic(BleUuids.PLX_SPOT_CHECK)
                char?.let { enqueueGattOp { enableNotifications(gatt, it) } }
            }

            // 6. Subscribe to Health Thermometer
            gatt.getService(BleUuids.HTS_SERVICE)?.let { service ->
                service.getCharacteristic(BleUuids.TEMPERATURE_MEASUREMENT)?.let { char ->
                    enqueueGattOp { enableNotifications(gatt, char) }
                }
            }

            // 7. Read Device Information (one-shot reads)
            gatt.getService(BleUuids.DEVICE_INFO_SERVICE)?.let { service ->
                service.getCharacteristic(BleUuids.MANUFACTURER_NAME)?.let { char ->
                    enqueueGattOp { gatt.readCharacteristic(char) }
                }
                service.getCharacteristic(BleUuids.MODEL_NUMBER)?.let { char ->
                    enqueueGattOp { gatt.readCharacteristic(char) }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristic(characteristic.uuid, value)
        }

        // Compat override for API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleCharacteristic(characteristic.uuid, value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            onGattOpComplete()
            if (status != BluetoothGatt.GATT_SUCCESS) return
            handleCharacteristicRead(characteristic.uuid, value)
        }

        // Compat override for API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            onGattOpComplete()
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val value = characteristic.value ?: return
            handleCharacteristicRead(characteristic.uuid, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            onGattOpComplete()
        }
    }

    // ─── CHARACTERISTIC HANDLING ──────────────────────────

    private fun handleCharacteristic(uuid: UUID, value: ByteArray) {
        when (uuid) {
            BleUuids.HEART_RATE_MEASUREMENT -> {
                val parsed = parseHeartRateMeasurement(value)
                updateReading(
                    heartRate = parsed.heartRate,
                    rrIntervals = parsed.rrIntervals,
                    energyExpended = parsed.energyExpended,
                    sensorContact = parsed.sensorContact
                )
            }
            BleUuids.BATTERY_LEVEL -> {
                val level = value.firstOrNull()?.toInt()?.and(0xFF)
                updateReading(batteryLevel = level)
            }
            BleUuids.RSC_MEASUREMENT -> {
                val cadence = parseRunningCadence(value)
                updateReading(cadence = cadence)
            }
            BleUuids.CSC_MEASUREMENT -> {
                // CSC cadence parsing handled via crank revolution data
                val cadence = parseCyclingCadence(value)
                if (cadence != null) updateReading(cadence = cadence)
            }
            BleUuids.PLX_CONTINUOUS, BleUuids.PLX_SPOT_CHECK -> {
                val spo2 = parseSpO2(value)
                if (spo2 != null) updateReading(spO2 = spo2)
            }
            BleUuids.TEMPERATURE_MEASUREMENT -> {
                val tempC = parseTemperature(value)
                if (tempC != null) updateReading(temperatureC = tempC)
            }
        }
    }

    private fun handleCharacteristicRead(uuid: UUID, value: ByteArray) {
        when (uuid) {
            BleUuids.BATTERY_LEVEL -> {
                val level = value.firstOrNull()?.toInt()?.and(0xFF)
                updateReading(batteryLevel = level)
            }
            BleUuids.MANUFACTURER_NAME -> {
                val name = String(value).trim()
                Log.i(TAG, "Manufacturer: $name")
                updateReading(manufacturerName = name)
            }
            BleUuids.MODEL_NUMBER -> {
                val model = String(value).trim()
                Log.i(TAG, "Model: $model")
                updateReading(modelNumber = model)
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
        } ?: onGattOpComplete() // No CCCD descriptor, move to next op
    }

    // ─── PARSERS ─────────────────────────────────────────

    /**
     * Parse Heart Rate Measurement per Bluetooth SIG spec (0x2A37).
     *
     * Flags byte layout:
     *   Bit 0: HR format (0=UINT8, 1=UINT16)
     *   Bit 1-2: Sensor contact
     *   Bit 3: Energy expended present
     *   Bit 4: RR-interval present
     */
    private data class HeartRateParsed(
        val heartRate: Int,
        val sensorContact: Boolean?,
        val energyExpended: Int?,
        val rrIntervals: List<Int>
    )

    private fun parseHeartRateMeasurement(data: ByteArray): HeartRateParsed {
        if (data.isEmpty()) return HeartRateParsed(0, null, null, emptyList())

        val flags = data[0].toInt() and 0xFF
        var offset = 1

        // Heart Rate value
        val hrFormat16 = flags and 0x01 != 0
        val heartRate = if (hrFormat16) {
            val hr = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            hr
        } else {
            val hr = data[offset].toInt() and 0xFF
            offset += 1
            hr
        }

        // Sensor contact
        val contactSupported = flags and 0x04 != 0
        val sensorContact = if (contactSupported) (flags and 0x02 != 0) else null

        // Energy expended (cumulative kJ)
        val energyPresent = flags and 0x08 != 0
        val energyExpended = if (energyPresent && offset + 1 < data.size) {
            val ee = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            ee
        } else null

        // RR-intervals (1/1024 seconds each, convert to ms)
        val rrPresent = flags and 0x10 != 0
        val rrIntervals = mutableListOf<Int>()
        if (rrPresent) {
            while (offset + 1 < data.size) {
                val rr = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                rrIntervals.add((rr * 1000) / 1024) // convert to ms
                offset += 2
            }
        }

        return HeartRateParsed(heartRate, sensorContact, energyExpended, rrIntervals)
    }

    /**
     * Parse Running Speed & Cadence Measurement (0x2A53).
     * Flags bit 0: stride length present
     * Flags bit 1: total distance present
     * Flags bit 2: walking/running status
     * Cadence is always at bytes 3-4 (UINT8, steps/min).
     */
    private fun parseRunningCadence(data: ByteArray): Int {
        if (data.size < 4) return 0
        // Byte 0: flags, Bytes 1-2: instantaneous speed, Byte 3: cadence
        return data[3].toInt() and 0xFF
    }

    /**
     * Parse Cycling Speed & Cadence Measurement (0x2A5B).
     * Returns crank RPM if crank revolution data is present.
     */
    private var lastCrankRevs: Int? = null
    private var lastCrankTime: Int? = null

    private fun parseCyclingCadence(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val hasCrank = flags and 0x02 != 0
        if (!hasCrank) return null

        // Skip wheel revolution data if present
        var offset = 1
        if (flags and 0x01 != 0) offset += 6 // 4 bytes wheel revs + 2 bytes last event time

        if (offset + 3 >= data.size) return null
        val crankRevs = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        val crankTime = (data[offset + 2].toInt() and 0xFF) or ((data[offset + 3].toInt() and 0xFF) shl 8)

        val rpm = if (lastCrankRevs != null && lastCrankTime != null) {
            val prevRevs = lastCrankRevs!!
            val prevTime = lastCrankTime!!
            val dRevs = crankRevs - prevRevs
            var dTime = crankTime - prevTime
            if (dTime < 0) dTime += 65536 // handle 16-bit wrap
            if (dTime > 0 && dRevs > 0) {
                (dRevs * 60 * 1024) / dTime // 1/1024s units
            } else null
        } else null

        lastCrankRevs = crankRevs
        lastCrankTime = crankTime
        return rpm
    }

    // ─── STATE UPDATES ───────────────────────────────────

    private fun updateReading(
        heartRate: Int? = null,
        rrIntervals: List<Int>? = null,
        energyExpended: Int? = null,
        sensorContact: Boolean? = null,
        batteryLevel: Int? = null,
        cadence: Int? = null,
        spO2: Int? = null,
        temperatureC: Float? = null,
        manufacturerName: String? = null,
        modelNumber: String? = null
    ) {
        val current = _latestReading.value ?: WearableReading(
            deviceName = currentDevice?.name ?: "",
            deviceType = currentDevice?.type ?: WearableType.UNKNOWN
        )

        val updated = current.copy(
            heartRate = heartRate ?: current.heartRate,
            rrIntervals = rrIntervals ?: current.rrIntervals,
            energyExpended = energyExpended ?: current.energyExpended,
            sensorContact = sensorContact ?: current.sensorContact,
            batteryLevel = batteryLevel ?: current.batteryLevel,
            cadence = cadence ?: current.cadence,
            spO2 = spO2 ?: current.spO2,
            temperatureC = temperatureC ?: current.temperatureC,
            timestamp = System.currentTimeMillis(),
            manufacturerName = manufacturerName ?: current.manufacturerName,
            modelNumber = modelNumber ?: current.modelNumber
        )

        _latestReading.value = updated
        _readings.tryEmit(updated)
    }

    // ─── SpO2 PARSER ────────────────────────────────────

    /**
     * Parse PLX Continuous/Spot-Check Measurement.
     * Byte 0: flags
     * Bytes 1-2: SpO2 (SFLOAT — IEEE 11073 16-bit float)
     *
     * Returns integer SpO2 percentage (0-100), or null if invalid.
     */
    private fun parseSpO2(data: ByteArray): Int? {
        if (data.size < 3) return null
        // SFLOAT: lower 12 bits = mantissa, upper 4 bits = exponent
        val raw = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        val mantissa = raw and 0x0FFF
        val exponent = (raw shr 12) and 0x0F
        // Simple conversion for typical SpO2 values (exponent usually 0 or -1)
        val exp = if (exponent > 7) exponent - 16 else exponent  // signed 4-bit
        val value = mantissa * Math.pow(10.0, exp.toDouble()).toFloat()
        return if (value in 50f..100f) value.toInt() else null
    }

    // ─── TEMPERATURE PARSER ──────────────────────────────

    /**
     * Parse Health Thermometer Measurement (0x2A1C).
     * Byte 0: flags (bit 0 = Fahrenheit if set, Celsius if clear)
     * Bytes 1-4: temperature as IEEE 11073 FLOAT (32-bit)
     *
     * Returns temperature in °C.
     */
    private fun parseTemperature(data: ByteArray): Float? {
        if (data.size < 5) return null
        val flags = data[0].toInt() and 0xFF
        // IEEE 11073 FLOAT: bytes 1-3 = 24-bit mantissa (signed), byte 4 = exponent (signed)
        val mantissa = (data[1].toInt() and 0xFF) or
                ((data[2].toInt() and 0xFF) shl 8) or
                ((data[3].toInt() and 0xFF) shl 16)
        // Sign-extend 24-bit mantissa
        val signedMantissa = if (mantissa and 0x800000 != 0) mantissa or (0xFF shl 24) else mantissa
        val exponent = data[4].toInt() // already signed byte
        val tempValue = signedMantissa * Math.pow(10.0, exponent.toDouble()).toFloat()

        // Convert Fahrenheit to Celsius if needed
        val isFahrenheit = flags and 0x01 != 0
        val tempC = if (isFahrenheit) (tempValue - 32f) * 5f / 9f else tempValue
        return if (tempC in 20f..45f) tempC else null // sanity range for body temp
    }

    // ─── DEVICE DETECTION ────────────────────────────────

    /**
     * Detect wearable brand from BLE device name.
     *
     * Supported devices:
     * - **Garmin**: Fenix, Forerunner, Venu, Vivoactive, Instinct, Enduro, Epix, Lily, Descent, Tactix, Quatix, MARQ
     * - **Samsung**: Galaxy Watch, Gear, SM-R models
     * - **Polar**: H10, H9, OH1, Verity Sense, Vantage, Grit X, Pacer, Ignite, Unite
     * - **Wahoo**: TICKR, KICKR, ELEMNT, RPM, SPEEDPLAY, POWRLINK, RIVAL
     * - **Suunto**: Suunto 3/5/7/9, Race, Peak, Vertical, Ambit, Spartan, Movesense
     * - **Fitbit**: Charge, Sense, Versa, Inspire, Luxe, Ionic
     * - **Xiaomi/Amazfit**: Mi Band, Smart Band, Amazfit Bip, GTR, GTS, T-Rex, Band
     * - **Huawei**: Huawei Watch, Band, GT series
     * - **COROS**: Pace, Apex, Vertix, DURA, POD
     * - **WHOOP**: WHOOP strap (BLE HR broadcast)
     * - **Apple Watch**: Broadcasts HR during active workouts
     * - **Wear OS**: Google Pixel Watch and other Wear OS devices
     */
    private fun detectWearableType(name: String): WearableType {
        val lower = name.lowercase()
        return when {
            // Garmin
            lower.contains("garmin")
                || lower.contains("fenix") || lower.contains("fēnix")
                || lower.contains("forerunner") || lower.contains("venu")
                || lower.contains("vivoactive") || lower.contains("vivomove")
                || lower.contains("vivosmart") || lower.contains("instinct")
                || lower.contains("enduro") || lower.contains("epix")
                || lower.contains("lily") || lower.contains("descent")
                || lower.contains("tactix") || lower.contains("quatix")
                || lower.contains("marq")                 -> WearableType.GARMIN

            // Samsung
            lower.contains("galaxy") || lower.contains("samsung")
                || lower.contains("sm-r") || lower.contains("gear")
                                                           -> WearableType.SAMSUNG

            // Polar — HR straps (H10, H9, OH1, Verity Sense) and sport watches
            lower.contains("polar")
                || lower.startsWith("h10") || lower.startsWith("h9")
                || lower.contains("oh1") || lower.contains("verity")
                || lower.contains("vantage") || lower.contains("grit x")
                || lower.contains("pacer") || lower.contains("ignite")
                || lower.contains("unite")                -> WearableType.POLAR

            // Wahoo — HR straps, bike sensors, GPS computers
            lower.contains("wahoo") || lower.contains("tickr")
                || lower.contains("kickr") || lower.contains("elemnt")
                || lower.contains("rpm") || lower.contains("speedplay")
                || lower.contains("powrlink") || lower.contains("rival")
                                                           -> WearableType.WAHOO

            // Suunto
            lower.contains("suunto") || lower.contains("movesense")
                || lower.contains("ambit") || lower.contains("spartan")
                                                           -> WearableType.SUUNTO

            // Fitbit
            lower.contains("fitbit") || lower.contains("charge")
                || lower.contains("sense") || lower.contains("versa")
                || lower.contains("inspire") || lower.contains("luxe")
                || lower.contains("ionic")                -> WearableType.FITBIT

            // Xiaomi / Amazfit
            lower.contains("xiaomi") || lower.contains("mi band")
                || lower.contains("smart band") || lower.contains("amazfit")
                || lower.contains("zepp")                 -> WearableType.XIAOMI

            // Huawei
            lower.contains("huawei") || lower.contains("honor band")
                || lower.startsWith("hw-") || lower.contains("huawei band")
                                                           -> WearableType.HUAWEI

            // COROS
            lower.contains("coros") || lower.contains("pace")
                || lower.contains("apex") || lower.contains("vertix")
                                                           -> WearableType.COROS

            // WHOOP
            lower.contains("whoop")                       -> WearableType.WHOOP

            // Apple Watch (broadcasts HR when in Workout mode or Gym equipment mode)
            lower.contains("apple watch") || lower.contains("apple")
                                                           -> WearableType.APPLE

            // Wear OS / Google Pixel Watch
            lower.contains("pixel watch") || lower.contains("wear os")
                || lower.contains("ticwatch") || lower.contains("fossil")
                || lower.contains("mobvoi") || lower.contains("montblanc summit")
                                                           -> WearableType.WEAR_OS

            else                                           -> WearableType.GENERIC_BLE
        }
    }
}

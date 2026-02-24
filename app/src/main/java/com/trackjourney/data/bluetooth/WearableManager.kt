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
                    Log.i(TAG, "Connected to ${currentDevice?.name}")
                    _connectionState.value = WearableConnectionState.Connected(currentDevice!!)
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

            // 5. Read Device Information (one-shot reads)
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
            val dRevs = crankRevs - lastCrankRevs!!
            var dTime = crankTime - lastCrankTime!!
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
            timestamp = System.currentTimeMillis(),
            manufacturerName = manufacturerName ?: current.manufacturerName,
            modelNumber = modelNumber ?: current.modelNumber
        )

        _latestReading.value = updated
        _readings.tryEmit(updated)
    }

    /**
     * Detect wearable brand from BLE device name.
     * Garmin Fenix variants: "fēnix", "fenix", "Fenix 7", "Garmin Fenix 7X", etc.
     * Also supports Forerunner, Venu, Vivoactive, Instinct, Enduro, Epix, Lily, Vivomove.
     */
    private fun detectWearableType(name: String): WearableType {
        val lower = name.lowercase()
        return when {
            lower.contains("garmin")
                || lower.contains("fenix")
                || lower.contains("fēnix")
                || lower.contains("forerunner")
                || lower.contains("venu")
                || lower.contains("vivoactive")
                || lower.contains("vivomove")
                || lower.contains("vivosmart")
                || lower.contains("instinct")
                || lower.contains("enduro")
                || lower.contains("epix")
                || lower.contains("lily")
                || lower.contains("descent")
                || lower.contains("tactix")
                || lower.contains("quatix")
                || lower.contains("marq")         -> WearableType.GARMIN
            lower.contains("galaxy")
                || lower.contains("samsung")
                || lower.contains("sm-r")
                || lower.contains("gear")          -> WearableType.SAMSUNG
            else                                    -> WearableType.GENERIC_BLE
        }
    }
}

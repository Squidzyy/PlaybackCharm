
package com.abdul.musicdisplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

class BleManager(context: Context) {

    companion object {
        private const val TAG = "MusicDisplayBLE"
        private const val SCAN_PERIOD = 10_000L
        private const val REQUESTED_MTU = 247
        private const val CONNECTION_TIMEOUT = 15_000L
        private const val DISCONNECT_TIMEOUT = 2_000L

        private const val RECONNECT_RETRY_DELAY = 2_000L
        private const val RECONNECT_SCAN_PERIOD = 10_000L
        private const val PREFS_NAME = "ble_connection_preferences"
        private const val KEY_REMEMBERED_DEVICE = "remembered_device_address"

        val SERVICE_UUID: UUID =
            UUID.fromString("7d2a0001-8f3b-4c6d-9e10-1234567890ab")

        val RX_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("7d2a0002-8f3b-4c6d-9e10-1234567890ab")

        val TX_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("7d2a0003-8f3b-4c6d-9e10-1234567890ab")

        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        READY
    }

    private val appContext = context.applicationContext

    private val bluetoothManager =
        appContext.getSystemService(BluetoothManager::class.java)

    private val bluetoothAdapter: BluetoothAdapter? =
        bluetoothManager?.adapter

    private val handler = Handler(Looper.getMainLooper())
    private val preferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var reconnectScanning = false
    private var reconnectScanCallback: ScanCallback? = null
    private var reconnectRunnable: Runnable? = null
    private var reconnectScanTimeoutRunnable: Runnable? = null

    private var bleScanner: BluetoothLeScanner? = null
    private var scanning = false

    private var bluetoothGatt: BluetoothGatt? = null

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private var negotiatedMtu = 23

    private val discoveredAddresses = mutableSetOf<String>()

    private val scanTimeoutRunnable = Runnable {
        stopScan()
    }

    private var connectionTimeoutRunnable: Runnable? = null
    private var disconnectTimeoutRunnable: Runnable? = null

    // --------------------------------------------------
    // BLUETOOTH STATE MONITORING
    // --------------------------------------------------

    private val bluetoothStateReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
                return
            }

            when (
                intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
            ) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> {
                    Log.w(TAG, "Bluetooth disabled. Releasing GATT.")

                    stopScan()
                    closeGatt()
                }

                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "Bluetooth enabled. Ready for connections.")
                    scheduleReconnect()
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                bluetoothStateReceiver,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    // --------------------------------------------------
    // CALLBACKS AND STATE
    // --------------------------------------------------

    var onTransmissionConnectionStateChanged:
            ((ConnectionState) -> Unit)? = null

    var onTransmissionDataReceived:
            ((ByteArray) -> Unit)? = null

    var connectionState = ConnectionState.DISCONNECTED
        private set

    var onDeviceFound: ((BluetoothDevice, Int) -> Unit)? = null
    var onScanFailed: ((Int) -> Unit)? = null
    var onScanStateChanged: ((Boolean) -> Unit)? = null

    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onMtuChanged: ((Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val maxWriteSize: Int
        get() = negotiatedMtu - 3

    private fun updateConnectionState(newState: ConnectionState) {
        if (connectionState == newState) return

        connectionState = newState

        Log.d(TAG, "Connection state: $newState")

        handler.post {
            onConnectionStateChanged?.invoke(newState)
            onTransmissionConnectionStateChanged?.invoke(newState)
        }
    }

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    appContext.checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            appContext.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // --------------------------------------------------
    // SCANNING
    // --------------------------------------------------

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val device = result.device

            try {
                val address = device.address

                if (discoveredAddresses.add(address)) {
                    Log.d(TAG, "Device found: ${device.name}")

                    handler.post {
                        onDeviceFound?.invoke(device, result.rssi)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Bluetooth permission missing", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")

            handler.removeCallbacks(scanTimeoutRunnable)
            scanning = false

            handler.post {
                onScanStateChanged?.invoke(false)
                onScanFailed?.invoke(errorCode)
            }
        }
    }

    fun startScan() {
        if (scanning) return

        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Bluetooth permissions not granted")
            onError?.invoke("Bluetooth permissions not granted")
            return
        }

        val adapter = bluetoothAdapter

        if (adapter == null) {
            onError?.invoke("Bluetooth not supported")
            return
        }

        try {
            if (!adapter.isEnabled) {
                onError?.invoke("Bluetooth is disabled")
                return
            }

            bleScanner = adapter.bluetoothLeScanner

            if (bleScanner == null) {
                onError?.invoke("BLE scanner unavailable")
                return
            }

            discoveredAddresses.clear()

            bleScanner?.startScan(scanCallback)

            scanning = true
            onScanStateChanged?.invoke(true)

            Log.d(TAG, "BLE scan started")

            handler.postDelayed(
                scanTimeoutRunnable,
                SCAN_PERIOD
            )

        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to start BLE scan", e)
            onError?.invoke("Unable to start BLE scan")
        }
    }

    fun stopScan() {
        handler.removeCallbacks(scanTimeoutRunnable)

        if (!scanning) return

        try {
            bleScanner?.stopScan(scanCallback)
            Log.d(TAG, "BLE scan stopped")

        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to stop BLE scan", e)

        } finally {
            scanning = false
            onScanStateChanged?.invoke(false)
        }
    }

    // --------------------------------------------------
    // CONNECTION
    // --------------------------------------------------


    fun connectToDevice(device: BluetoothDevice) {
        // Remember the first manually selected device.
        preferences.edit()
            .putString(KEY_REMEMBERED_DEVICE, device.address)
            .apply()

        Log.d(TAG, "Saved device for automatic reconnection: ${device.address}")

        connectToDeviceInternal(device)
    }


    fun connectToDeviceInternal(device: BluetoothDevice) {
        cancelReconnectWork()

        if (!hasRequiredPermissions()) {
            onError?.invoke("Bluetooth permissions not granted")
            return
        }

        val adapter = bluetoothAdapter

        if (adapter == null || !adapter.isEnabled) {
            onError?.invoke("Bluetooth is disabled or unavailable")
            return
        }

        // Do not create another connection while one is active.
        if (bluetoothGatt != null) {
            if (connectionState != ConnectionState.DISCONNECTED) {
                Log.w(TAG, "Connection already active or in progress")
                onError?.invoke(
                    "Disconnect from the current device before connecting again"
                )
                return
            }

            // Release any leftover GATT object.
            closeGatt()
        }

        // Recover from an inconsistent state.
        if (connectionState != ConnectionState.DISCONNECTED) {
            updateConnectionState(ConnectionState.DISCONNECTED)
        }

        stopScan()

        try {
            Log.d(TAG, "Starting GATT connection to ${device.address}")

            updateConnectionState(ConnectionState.CONNECTING)

            val newGatt = device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            if (newGatt == null) {
                Log.e(TAG, "connectGatt returned null")
                updateConnectionState(ConnectionState.DISCONNECTED)
                onError?.invoke("Unable to create BLE connection")
                return
            }

            bluetoothGatt = newGatt

            // Prevent an attempt from remaining CONNECTING indefinitely.
            val timeout = Runnable {
                if (
                    bluetoothGatt === newGatt &&
                    connectionState == ConnectionState.CONNECTING
                ) {
                    Log.e(TAG, "GATT connection timed out")

                    onError?.invoke("BLE connection timed out")
                    closeGatt(newGatt)
                }
            }

            connectionTimeoutRunnable = timeout

            handler.postDelayed(
                timeout,
                CONNECTION_TIMEOUT
            )

        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to connect", e)
            closeGatt()
            onError?.invoke("Unable to connect to device")

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected connection error", e)
            closeGatt()
            onError?.invoke("Unexpected BLE connection error: ${e.message}")
        }
    }

    fun disconnect() {
        val gatt = bluetoothGatt

        if (gatt == null) {
            closeGatt()
            return
        }

        Log.d(TAG, "Disconnect requested")

        try {
            gatt.disconnect()

            // Android should normally deliver STATE_DISCONNECTED.
            // Force cleanup if that callback never arrives.
            val timeout = Runnable {
                if (bluetoothGatt === gatt) {
                    Log.w(TAG, "Disconnect callback timed out. Forcing cleanup.")
                    closeGatt(gatt)
                }
            }

            disconnectTimeoutRunnable = timeout

            handler.postDelayed(
                timeout,
                DISCONNECT_TIMEOUT
            )

        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to disconnect", e)
            closeGatt(gatt)
        }
    }

    // --------------------------------------------------
// AUTOMATIC RECONNECTION
// --------------------------------------------------

    private fun cancelReconnectWork() {
        reconnectRunnable?.let {
            handler.removeCallbacks(it)
        }
        reconnectRunnable = null

        reconnectScanTimeoutRunnable?.let {
            handler.removeCallbacks(it)
        }
        reconnectScanTimeoutRunnable = null

        stopReconnectScan()
    }

    private fun stopReconnectScan() {
        val callback = reconnectScanCallback
        val scanner = bluetoothAdapter?.bluetoothLeScanner

        if (reconnectScanning && callback != null && scanner != null) {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping reconnect scan", e)
            }
        }

        reconnectScanning = false
        reconnectScanCallback = null
    }

    private fun scheduleReconnect() {
        val address = preferences.getString(KEY_REMEMBERED_DEVICE, null)
            ?: return

        if (connectionState != ConnectionState.DISCONNECTED) return
        if (bluetoothGatt != null) return
        if (reconnectScanning || reconnectRunnable != null) return

        val adapter = bluetoothAdapter

        if (adapter == null || !adapter.isEnabled) {
            Log.d(TAG, "Waiting for Bluetooth to become available")
            return
        }

        val retry = Runnable {
            reconnectRunnable = null
            startReconnectScan(address)
        }

        reconnectRunnable = retry

        handler.postDelayed(
            retry,
            RECONNECT_RETRY_DELAY
        )

        Log.d(TAG, "Reconnect scheduled for $address")
    }

    private fun startReconnectScan(targetAddress: String) {
        if (connectionState != ConnectionState.DISCONNECTED) return
        if (bluetoothGatt != null) return

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Cannot reconnect: Bluetooth permissions missing")
            return
        }

        val adapter = bluetoothAdapter ?: return

        if (!adapter.isEnabled) return

        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot access BLE scanner", e)
            return
        }

        if (scanner == null) {
            Log.w(TAG, "BLE scanner unavailable")
            scheduleReconnect()
            return
        }

        val callback = object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                val device = result.device

                val address = try {
                    device.address
                } catch (e: SecurityException) {
                    Log.e(TAG, "Cannot read Bluetooth address", e)
                    return
                }

                // Ignore every device except the one manually selected.
                if (!address.equals(targetAddress, ignoreCase = true)) {
                    return
                }

                Log.d(TAG, "Remembered ESP32 found: $address")

                stopReconnectScan()

                handler.post {
                    if (
                        connectionState == ConnectionState.DISCONNECTED &&
                        bluetoothGatt == null
                    ) {
                        Log.d(TAG, "Automatically reconnecting to ESP32")
                        connectToDeviceInternal(device)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Reconnect scan failed: $errorCode")

                reconnectScanning = false
                reconnectScanCallback = null

                scheduleReconnect()
            }
        }

        try {
            reconnectScanCallback = callback
            reconnectScanning = true

            scanner.startScan(callback)

            Log.d(TAG, "Scanning for remembered ESP32: $targetAddress")

            val timeout = Runnable {
                reconnectScanTimeoutRunnable = null

                stopReconnectScan()

                Log.d(TAG, "ESP32 not found. Retrying scan.")

                scheduleReconnect()
            }

            reconnectScanTimeoutRunnable = timeout

            handler.postDelayed(
                timeout,
                RECONNECT_SCAN_PERIOD
            )

        } catch (e: SecurityException) {
            reconnectScanning = false
            reconnectScanCallback = null

            Log.e(TAG, "Reconnect scan permission error", e)
            scheduleReconnect()

        } catch (e: Exception) {
            reconnectScanning = false
            reconnectScanCallback = null

            Log.e(TAG, "Unable to start reconnect scan", e)
            scheduleReconnect()
        }
    }
    // --------------------------------------------------
    // GATT CLEANUP
    // --------------------------------------------------

    private fun closeGatt(gatt: BluetoothGatt? = bluetoothGatt) {

        if (gatt == null) {
            cancelConnectionTimeout()
            cancelDisconnectTimeout()

            bluetoothGatt = null
            rxCharacteristic = null
            txCharacteristic = null
            negotiatedMtu = 23

            updateConnectionState(ConnectionState.DISCONNECTED)
            scheduleReconnect()
            return
        }

        val isCurrentConnection = bluetoothGatt === gatt

        // Only the current GATT is allowed to modify manager state.
        if (isCurrentConnection) {
            bluetoothGatt = null

            cancelConnectionTimeout()
            cancelDisconnectTimeout()

            rxCharacteristic = null
            txCharacteristic = null
            negotiatedMtu = 23

            updateConnectionState(ConnectionState.DISCONNECTED)
        }

        // Close the actual Android GATT object.
        try {
            gatt.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to close GATT", e)
        }

        if (isCurrentConnection) {
            Log.d(TAG, "GATT resources released.")
            scheduleReconnect()
        } else {
            Log.d(TAG, "Closed obsolete GATT connection.")
        }
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutRunnable?.let {
            handler.removeCallbacks(it)
        }
        connectionTimeoutRunnable = null
    }

    private fun cancelDisconnectTimeout() {
        disconnectTimeoutRunnable?.let {
            handler.removeCallbacks(it)
        }
        disconnectTimeoutRunnable = null
    }

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean {
        if (gatt === bluetoothGatt) return true

        Log.d(TAG, "Ignoring callback from obsolete GATT connection")

        try {
            gatt.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to close obsolete GATT", e)
        }

        return false
    }

    // --------------------------------------------------
    // GATT CALLBACKS
    // --------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (!isCurrentGatt(gatt)) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT connection error: $status")

                onError?.let { callback ->
                    handler.post {
                        callback("GATT connection error: $status")
                    }
                }

                closeGatt(gatt)
                return
            }

            when (newState) {

                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected! Discovering services...")

                    cancelConnectionTimeout()
                    cancelDisconnectTimeout()

                    updateConnectionState(ConnectionState.CONNECTED)

                    try {
                        if (!gatt.discoverServices()) {
                            Log.e(TAG, "Service discovery could not be started")
                            onError?.invoke("Could not start service discovery")
                            closeGatt(gatt)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Service discovery permission error", e)
                        closeGatt(gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Device disconnected")

                    closeGatt(gatt)
                }
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                onError?.invoke("Service discovery failed")
                closeGatt(gatt)
                return
            }

            val service = gatt.getService(SERVICE_UUID)

            if (service == null) {
                Log.e(TAG, "Music Display service not found")
                onError?.invoke("Music Display service not found")
                closeGatt(gatt)
                return
            }

            rxCharacteristic =
                service.getCharacteristic(RX_CHARACTERISTIC_UUID)

            txCharacteristic =
                service.getCharacteristic(TX_CHARACTERISTIC_UUID)

            if (rxCharacteristic == null || txCharacteristic == null) {
                Log.e(TAG, "Required characteristics not found")
                onError?.invoke("Required characteristics not found")
                closeGatt(gatt)
                return
            }

            Log.d(TAG, "Required GATT characteristics found")

            try {
                val mtuRequested = gatt.requestMtu(REQUESTED_MTU)

                if (!mtuRequested) {
                    Log.w(TAG, "MTU request could not be started")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Unable to request MTU", e)
            }

            enableNotifications(gatt)
        }

        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.d(TAG, "Negotiated MTU: $mtu")
                onMtuChanged?.invoke(mtu)
            } else {
                Log.w(TAG, "MTU negotiation failed; using $negotiatedMtu")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return
            if (descriptor.uuid != CCCD_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "ESP32 notifications enabled")
                updateConnectionState(ConnectionState.READY)
            } else {
                Log.e(TAG, "Failed to enable notifications: $status")
                onError?.invoke("Failed to enable ESP32 notifications")
                closeGatt(gatt)
            }
        }

        @Deprecated("Deprecated on Android 13; newer overload is also provided")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!isCurrentGatt(gatt)) return

            if (characteristic.uuid == TX_CHARACTERISTIC_UUID) {
                characteristic.value?.let { data ->
                    val copy = data.copyOf()

                    handler.post {
                        onDataReceived?.invoke(copy)
                        onTransmissionDataReceived?.invoke(copy)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!isCurrentGatt(gatt)) return

            if (characteristic.uuid == TX_CHARACTERISTIC_UUID) {
                val data = value.copyOf()

                handler.post {
                    onDataReceived?.invoke(data)
                    onTransmissionDataReceived?.invoke(data)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Packet written successfully")
            } else {
                Log.e(TAG, "Characteristic write failed: $status")
                onError?.invoke("BLE packet write failed: $status")
            }
        }
    }

    // --------------------------------------------------
    // NOTIFICATIONS
    // --------------------------------------------------

    private fun enableNotifications(gatt: BluetoothGatt) {
        if (!isCurrentGatt(gatt)) return

        val characteristic = txCharacteristic

        if (characteristic == null) {
            onError?.invoke("TX characteristic unavailable")
            closeGatt(gatt)
            return
        }

        try {
            val enabled = gatt.setCharacteristicNotification(
                characteristic,
                true
            )

            if (!enabled) {
                onError?.invoke("Could not enable local notifications")
                closeGatt(gatt)
                return
            }

            val descriptor = characteristic.getDescriptor(CCCD_UUID)

            if (descriptor == null) {
                onError?.invoke("Notification descriptor not found")
                closeGatt(gatt)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                val result = gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )

                if (result != BluetoothStatusCodes.SUCCESS) {
                    Log.e(TAG, "Could not configure notifications: $result")
                    onError?.invoke("Could not configure notifications")
                    closeGatt(gatt)
                }

            } else {
                @Suppress("DEPRECATION")
                descriptor.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                @Suppress("DEPRECATION")
                val success = gatt.writeDescriptor(descriptor)

                if (!success) {
                    onError?.invoke("Could not configure notifications")
                    closeGatt(gatt)
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Notification setup failed", e)
            onError?.invoke("Notification setup failed")
            closeGatt(gatt)
        }
    }

    // --------------------------------------------------
    // DATA TRANSMISSION
    // --------------------------------------------------

    fun writePacket(data: ByteArray): Boolean {

        if (connectionState != ConnectionState.READY) {
            Log.e(TAG, "Cannot write: GATT is not ready")
            return false
        }

        val gatt = bluetoothGatt ?: return false
        val characteristic = rxCharacteristic ?: return false

        if (data.size > negotiatedMtu - 3) {
            Log.e(TAG, "Packet too large for negotiated MTU")
            return false
        }

        return try {
            val properties = characteristic.properties

            val writeType =
                if (
                    properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                ) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                } else if (
                    properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                ) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                } else {
                    onError?.invoke("ESP32 RX characteristic is not writable")
                    return false
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                val result = gatt.writeCharacteristic(
                    characteristic,
                    data,
                    writeType
                )

                result == BluetoothStatusCodes.SUCCESS

            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType

                @Suppress("DEPRECATION")
                characteristic.value = data

                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Packet write permission error", e)
            false
        }
    }

    fun getNegotiatedMtu(): Int {
        return negotiatedMtu
    }
}

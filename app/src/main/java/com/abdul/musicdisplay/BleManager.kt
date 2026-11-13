
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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

class BleManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "MusicDisplayBLE"
        private const val SCAN_PERIOD = 10_000L
        private const val REQUESTED_MTU = 247


        // Our custom GATT UUIDs
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

    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)

    private val bluetoothAdapter: BluetoothAdapter? =
        bluetoothManager?.adapter

    private var bleScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())

    private var scanning = false
    private var bluetoothGatt: BluetoothGatt? = null

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private var negotiatedMtu = 23

    val maxWriteSize: Int
        get() = negotiatedMtu - 3

    var connectionState = ConnectionState.DISCONNECTED
        private set

    var onDeviceFound: ((BluetoothDevice, Int) -> Unit)? = null
    var onScanFailed: ((Int) -> Unit)? = null
    var onScanStateChanged: ((Boolean) -> Unit)? = null

    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onMtuChanged: ((Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val discoveredAddresses = mutableSetOf<String>()

    private fun updateConnectionState(newState: ConnectionState) {
        connectionState = newState

        handler.post {
            onConnectionStateChanged?.invoke(newState)
        }

        Log.d(TAG, "Connection state: $newState")
    }

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(
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

            scanning = false
            handler.removeCallbacksAndMessages(null)

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

            handler.postDelayed({
                stopScan()
            }, SCAN_PERIOD)

        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to start BLE scan", e)
            onError?.invoke("Unable to start BLE scan")
        }
    }

    fun stopScan() {
        if (!scanning) return

        try {
            bleScanner?.stopScan(scanCallback)
            Log.d(TAG, "BLE scan stopped")

        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to stop BLE scan", e)

        } finally {
            scanning = false
            onScanStateChanged?.invoke(false)
            handler.removeCallbacksAndMessages(null)
        }
    }

    // --------------------------------------------------
    // CONNECTION
    // --------------------------------------------------

    fun connectToDevice(device: BluetoothDevice) {
        if (!hasRequiredPermissions()) {
            onError?.invoke("Bluetooth permissions not granted")
            return
        }

        if (bluetoothGatt != null) {
            onError?.invoke("Disconnect from the current device before connecting to another")
            return
        }

        stopScan()

        try {
            updateConnectionState(ConnectionState.CONNECTING)

            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            Log.d(TAG, "Connecting to ${device.address}")

        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to connect", e)
            onError?.invoke("Unable to connect to device")
            updateConnectionState(ConnectionState.DISCONNECTED)
        }
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to disconnect", e)
            closeGatt()
        }
    }

    private fun closeGatt() {
        try {
            bluetoothGatt?.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "Unable to close GATT", e)
        }

        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
        negotiatedMtu = 23

        updateConnectionState(ConnectionState.DISCONNECTED)
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT connection error: $status")
                onError?.let { callback ->
                    handler.post {
                        callback("GATT connection error: $status")
                    }
                }
                closeGatt()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected! Discovering services...")

                    updateConnectionState(ConnectionState.CONNECTED)

                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Service discovery permission error", e)
                        closeGatt()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Device disconnected")
                    closeGatt()
                }
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                onError?.invoke("Service discovery failed")
                closeGatt()
                return
            }

            val service = gatt.getService(SERVICE_UUID)

            if (service == null) {
                Log.e(TAG, "Music Display service not found")
                onError?.invoke("Music Display service not found")
                closeGatt()
                return
            }

            rxCharacteristic =
                service.getCharacteristic(RX_CHARACTERISTIC_UUID)

            txCharacteristic =
                service.getCharacteristic(TX_CHARACTERISTIC_UUID)

            if (rxCharacteristic == null || txCharacteristic == null) {
                Log.e(TAG, "Required characteristics not found")
                onError?.invoke("Required characteristics not found")
                closeGatt()
                return
            }

            Log.d(TAG, "Required GATT characteristics found")

            try {
                gatt.requestMtu(REQUESTED_MTU)
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
            if (descriptor.uuid != CCCD_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "ESP32 notifications enabled")
                updateConnectionState(ConnectionState.READY)
            } else {
                Log.e(TAG, "Failed to enable notifications: $status")
                onError?.invoke("Failed to enable ESP32 notifications")
            }
        }

        @Deprecated("Deprecated on Android 13; newer overload is also provided")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == TX_CHARACTERISTIC_UUID) {
                characteristic.value?.let { data ->
                    handler.post {
                        onDataReceived?.invoke(data.copyOf())
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_CHARACTERISTIC_UUID) {
                val data = value.copyOf()

                handler.post {
                    onDataReceived?.invoke(data)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
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
        val characteristic = txCharacteristic ?: return

        try {
            val enabled = gatt.setCharacteristicNotification(
                characteristic,
                true
            )

            if (!enabled) {
                onError?.invoke("Could not enable local notifications")
                return
            }

            val descriptor = characteristic.getDescriptor(CCCD_UUID)

            if (descriptor == null) {
                onError?.invoke("Notification descriptor not found")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )

                if (result != BluetoothStatusCodes.SUCCESS) {
                    onError?.invoke("Could not configure notifications")
                }
            } else {
                @Suppress("DEPRECATION")
                descriptor.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                @Suppress("DEPRECATION")
                val success = gatt.writeDescriptor(descriptor)

                if (!success) {
                    onError?.invoke("Could not configure notifications")
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Notification setup failed", e)
            onError?.invoke("Notification setup failed")
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

        // ATT write payload cannot exceed MTU - 3 bytes.
        if (data.size > negotiatedMtu - 3) {
            Log.e(TAG, "Packet too large for negotiated MTU")
            return false
        }

        return try {
            val properties = characteristic.properties

            val writeType =
                if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else if (
                    properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
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

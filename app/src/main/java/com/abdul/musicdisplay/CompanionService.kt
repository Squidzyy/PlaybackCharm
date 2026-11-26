package com.abdul.musicdisplay

import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

class CompanionService : CompanionDeviceService() {

    companion object {
        private const val TAG = "PlaybackCharmCompanion"
    }

    private fun connectToDisplay(address: String?) {

        if (address.isNullOrBlank()) {
            Log.e(TAG, "Cannot connect: device address unavailable")
            return
        }

        try {
            val bleManager = BleConnection.get(applicationContext)

            if (bleManager.connectionState !=
                BleManager.ConnectionState.DISCONNECTED
            ) {
                Log.d(TAG, "BLE already connecting or connected")
                return
            }

            val bluetoothManager =
                getSystemService(BluetoothManager::class.java)

            val device =
                bluetoothManager?.adapter?.getRemoteDevice(address)

            if (device == null) {
                Log.e(TAG, "Bluetooth device unavailable")
                return
            }

            Log.d(TAG, "ESP32 appeared. Connecting to $address")

            bleManager.connectToDevice(device)

        } catch (e: Exception) {
            Log.e(TAG, "Automatic BLE connection failed", e)
        }
    }

    private fun disconnectFromDisplay() {
        Log.d(TAG, "ESP32 disappeared. Leaving BLE reconnection to BleManager.")
    }

    // Android 12 (API 31) and 12L (API 32)
    @Deprecated("Use the AssociationInfo overload")
    override fun onDeviceAppeared(address: String) {
        super.onDeviceAppeared(address)
        connectToDisplay(address)
    }

    @Deprecated("Use the AssociationInfo overload")
    override fun onDeviceDisappeared(address: String) {
        super.onDeviceDisappeared(address)
        disconnectFromDisplay()
    }

    // Android 13 through Android 15
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(
        associationInfo: AssociationInfo
    ) {
        super.onDeviceAppeared(associationInfo)

        connectToDisplay(
            associationInfo.deviceMacAddress?.toString()
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(
        associationInfo: AssociationInfo
    ) {
        super.onDeviceDisappeared(associationInfo)
        disconnectFromDisplay()
    }

    // Android 16 and newer
    @RequiresApi(36)
    override fun onDevicePresenceEvent(
        event: DevicePresenceEvent
    ) {
        super.onDevicePresenceEvent(event)

        val associationManager =
            getSystemService(CompanionDeviceManager::class.java)

        val association = associationManager.myAssociations.firstOrNull {
            it.id == event.associationId
        }

        val address =
            association?.deviceMacAddress?.toString()

        when (event.event) {

            DevicePresenceEvent.EVENT_BLE_APPEARED -> {
                Log.d(TAG, "ESP32 BLE presence detected")
                connectToDisplay(address)
            }

            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> {
                Log.d(TAG, "ESP32 BLE presence lost")
                disconnectFromDisplay()
            }
        }
    }
}
package com.abdul.musicdisplay

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.AssociationInfo
import android.companion.ObservingDevicePresenceRequest
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.regex.Pattern
import android.bluetooth.BluetoothManager

object CompanionDeviceSetup {

    const val REQUEST_CODE = 9124

    fun associate(activity: MainActivity) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(
                activity,
                "Automatic connection requires Android 12 or newer.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val manager =
            activity.getSystemService(CompanionDeviceManager::class.java)
        BleConnection.get(activity).stopScan()

        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("^MusicDisplay$"))
            .build()

        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                manager.associate(
                    request,
                    activity.mainExecutor,
                    object : CompanionDeviceManager.Callback() {

                        override fun onAssociationPending(
                            intentSender: IntentSender
                        ) {
                            try {
                                activity.startIntentSenderForResult(
                                    intentSender,
                                    REQUEST_CODE,
                                    null,
                                    0,
                                    0,
                                    0
                                )
                            } catch (e: IntentSender.SendIntentException) {
                                Toast.makeText(
                                    activity,
                                    "Unable to open device selection.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onAssociationCreated(
                            associationInfo: AssociationInfo
                        ) {
                            startPresenceObservation(
                                activity,
                                associationInfo
                            )
                        }

                        override fun onFailure(errorMessage: CharSequence?) {
                            Toast.makeText(
                                activity,
                                "Association failed: $errorMessage",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )

            } else {

                @Suppress("DEPRECATION")
                manager.associate(
                    request,
                    object : CompanionDeviceManager.Callback() {

                        override fun onDeviceFound(
                            chooserLauncher: IntentSender
                        ) {
                            try {
                                activity.startIntentSenderForResult(
                                    chooserLauncher,
                                    REQUEST_CODE,
                                    null,
                                    0,
                                    0,
                                    0
                                )
                            } catch (e: IntentSender.SendIntentException) {
                                Toast.makeText(
                                    activity,
                                    "Unable to open device selection.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onFailure(error: CharSequence?) {
                            Toast.makeText(
                                activity,
                                "Association failed: $error",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }

        } catch (e: Exception) {
            Toast.makeText(
                activity,
                "Could not start device association: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startPresenceObservation(
        activity: MainActivity,
        associationInfo: AssociationInfo
    ) {
        try {
            val manager =
                activity.getSystemService(CompanionDeviceManager::class.java)

            if (Build.VERSION.SDK_INT >= 36) {

                val request = ObservingDevicePresenceRequest.Builder()
                    .setAssociationId(associationInfo.id)
                    .build()

                manager.startObservingDevicePresence(request)

            } else {

                val address =
                    associationInfo.deviceMacAddress?.toString()

                if (address.isNullOrBlank()) {
                    Toast.makeText(
                        activity,
                        "Device address unavailable.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }

                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address)
            }

            // Immediately connect to the device selected during association.
// Future connections will be handled by CompanionService.

            val address = associationInfo.deviceMacAddress?.toString()

            if (address.isNullOrBlank()) {
                Toast.makeText(
                    activity,
                    "Associated, but Bluetooth address unavailable.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val bluetoothManager =
                activity.getSystemService(BluetoothManager::class.java)

            val device = bluetoothManager?.adapter?.getRemoteDevice(address)

            if (device == null) {
                Toast.makeText(
                    activity,
                    "Could not retrieve Bluetooth device.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            BleConnection.get(activity).connectToDevice(device)

            Toast.makeText(
                activity,
                "MusicDisplay associated. Automatic connection enabled.",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                activity,
                "Could not enable automatic connection: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun handleLegacyActivityResult(
        activity: MainActivity,
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?
    ) {
        if (requestCode != REQUEST_CODE) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return

        if (resultCode != Activity.RESULT_OK) {
            Toast.makeText(
                activity,
                "Device association cancelled.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        @Suppress("DEPRECATION")
        val device = data?.getParcelableExtra<BluetoothDevice>(
            CompanionDeviceManager.EXTRA_DEVICE
        )

        if (device == null) {
            Toast.makeText(
                activity,
                "Could not retrieve selected device.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val manager =
                activity.getSystemService(CompanionDeviceManager::class.java)

            @Suppress("DEPRECATION")
            manager.startObservingDevicePresence(device.address)

            Toast.makeText(
                activity,
                "MusicDisplay associated. Automatic connection enabled.",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                activity,
                "Could not enable automatic connection: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
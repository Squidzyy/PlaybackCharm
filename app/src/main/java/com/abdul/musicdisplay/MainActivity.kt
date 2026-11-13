
package com.abdul.musicdisplay

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class DiscoveredDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {

                val bleManager = remember {
                    BleManager(this@MainActivity)
                }

                val discoveredDevices =
                    remember { mutableStateListOf<DiscoveredDevice>() }

                var isScanning by remember {
                    mutableStateOf(false)
                }

                var connectionState by remember {
                    mutableStateOf(BleManager.ConnectionState.DISCONNECTED)
                }

                var status by remember {
                    mutableStateOf("Ready to scan")
                }

                var receivedData by remember {
                    mutableStateOf("No data received")
                }

                // Receive discovered BLE devices.
                bleManager.onDeviceFound = { device, rssi ->

                    runOnUiThread {
                        try {
                            val address = device.address
                            val name = device.name ?: "Unnamed BLE device"

                            val existingIndex =
                                discoveredDevices.indexOfFirst {
                                    it.address == address
                                }

                            val entry = DiscoveredDevice(
                                device = device,
                                name = name,
                                address = address,
                                rssi = rssi
                            )

                            if (existingIndex >= 0) {
                                discoveredDevices[existingIndex] = entry
                            } else {
                                discoveredDevices.add(entry)
                            }

                        } catch (e: SecurityException) {
                            status = "Bluetooth permission error"
                        }
                    }
                }

                bleManager.onScanStateChanged = { scanning ->
                    isScanning = scanning

                    if (!scanning && status == "Scanning...") {
                        status = "Scan complete"
                    }
                }

                bleManager.onScanFailed = { errorCode ->
                    status = "Scan failed: $errorCode"
                }

                bleManager.onConnectionStateChanged = { newState ->
                    connectionState = newState
                    status = "Connection: $newState"
                }

                bleManager.onDataReceived = { data ->
                    receivedData = "Received ${data.size} bytes"
                }

                bleManager.onMtuChanged = { mtu ->
                    status = "Connected. Negotiated MTU: $mtu"
                }

                bleManager.onError = { message ->
                    runOnUiThread {
                        status = message
                    }
                }

                // Android runtime Bluetooth permissions.
                val permissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->

                        val allGranted =
                            permissions.values.all { it }

                        if (allGranted) {
                            discoveredDevices.clear()
                            status = "Scanning..."
                            bleManager.startScan()
                        } else {
                            status = "Bluetooth permissions denied"
                        }
                    }

                fun startBluetoothScan() {
                    if (bleManager.hasRequiredPermissions()) {
                        discoveredDevices.clear()
                        status = "Scanning..."
                        bleManager.startScan()
                    } else {
                        val permissions =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                )
                            } else {
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                            }

                        permissionLauncher.launch(permissions)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {

                    Text(
                        text = "Music Display - BLE Test",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Status: $status")

                    Text("Connection: $connectionState")

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { startBluetoothScan() },
                            enabled = !isScanning
                        ) {
                            Text("Scan for devices")
                        }

                        OutlinedButton(
                            onClick = { bleManager.stopScan() },
                            enabled = isScanning
                        ) {
                            Text("Stop")
                        }
                    }

                    if (connectionState !=
                        BleManager.ConnectionState.DISCONNECTED
                    ) {
                        OutlinedButton(
                            onClick = { bleManager.disconnect() }
                        ) {
                            Text("Disconnect")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Discovered devices (${discoveredDevices.size})",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            discoveredDevices,
                            key = { it.address }
                        ) { entry ->

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.titleSmall
                                )

                                Text("Address: ${entry.address}")
                                Text("Signal: ${entry.rssi} dBm")

                                Button(
                                    onClick = {
                                        status = "Connecting to ${entry.name}..."
                                        bleManager.connectToDevice(entry.device)
                                    }
                                ) {
                                    Text("Connect")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Incoming data: $receivedData")
                }
            }
        }
    }
}

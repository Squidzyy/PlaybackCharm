
package com.abdul.musicdisplay

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

class MediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MusicDisplay"
    }

    private val musicDataStore = MusicDataStore()

    private lateinit var bleManager: BleManager
    private lateinit var transmissionManager: TransmissionManager

    private val handler = Handler(Looper.getMainLooper())

    private val artworkCheckRunnable = Runnable {
        if (::transmissionManager.isInitialized) {
            transmissionManager.onUpdateAvailable()
        }
    }

    private lateinit var mediaSessionManager: MediaSessionManager

    private val registeredCallbacks =
        mutableMapOf<MediaController, MediaController.Callback>()

    private var currentController: MediaController? = null

    var currentMusicData: MusicData? = null
        private set

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            Log.d(TAG, "ACTIVE SESSIONS CHANGED!")
            updateSessions(controllers)
        }

    override fun onListenerConnected() {
        super.onListenerConnected()

        Log.d(TAG, "LISTENER CONNECTED!")

        // Retrieve the same BLE manager used by MainActivity.
        bleManager = BleConnection.get(this)

        // Initialize the transmission pipeline.
        initializeTransmission()

        mediaSessionManager =
            getSystemService(MediaSessionManager::class.java)

        val component = ComponentName(
            this,
            MediaListenerService::class.java
        )

        mediaSessionManager.addOnActiveSessionsChangedListener(
            sessionListener,
            component
        )

        val sessions = mediaSessionManager.getActiveSessions(component)
        updateSessions(sessions)
    }

    private fun initializeTransmission() {

        transmissionManager = TransmissionManager(
            dataStore = musicDataStore,

            // Send the encoded protocol packet through real BLE.
            sendBlePacket = { bytes ->
                val accepted = bleManager.writePacket(bytes)

                if (accepted) {
                    Log.d(TAG, "BLE accepted packet: ${bytes.size} bytes")
                } else {
                    Log.e(TAG, "BLE rejected packet")
                }

                accepted
            },

            // Use the actual negotiated BLE write size.
            maxWriteSize = {
                bleManager.maxWriteSize
            },

            onError = { error ->
                Log.e(TAG, "Transmission error: $error")
            }
        )

        // Route ESP32 notifications (including ACKs) to TransmissionManager.
        bleManager.onTransmissionDataReceived = { data ->
            transmissionManager.onPacketReceived(data)
        }

        // Start or stop transmission according to the actual BLE state.
        bleManager.onTransmissionConnectionStateChanged = { state ->
            when (state) {
                BleManager.ConnectionState.READY -> {
                    Log.d(TAG, "BLE READY - starting transmission")
                    transmissionManager.onConnected()
                }

                BleManager.ConnectionState.DISCONNECTED -> {
                    Log.d(TAG, "BLE DISCONNECTED")
                    transmissionManager.onDisconnected()
                }

                else -> Unit
            }
        }

        // Handle the case where BLE was already connected before
        // the service registered its callbacks.
        if (bleManager.connectionState == BleManager.ConnectionState.READY) {
            transmissionManager.onConnected()
        }

        Log.d(TAG, "Real BLE transmission initialized.")
    }

    private fun updateSessions(controllers: List<MediaController>?) {

        val activeControllers = controllers.orEmpty()

        val removedControllers = registeredCallbacks.keys.filter { oldController ->
            activeControllers.none {
                it.sessionToken == oldController.sessionToken
            }
        }

        for (controller in removedControllers) {
            val callback = registeredCallbacks.remove(controller)

            if (callback != null) {
                controller.unregisterCallback(callback)
            }
        }

        for (session in activeControllers) {

            if (!registeredCallbacks.containsKey(session)) {

                Log.d(TAG, "Tracking: ${session.packageName}")

                val callback = object : MediaController.Callback() {

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        super.onMetadataChanged(metadata)

                        if (session == currentController) {
                            updateMusicData(session)
                        }
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        super.onPlaybackStateChanged(state)

                        if (session == currentController) {
                            updateMusicData(session)
                        }
                    }
                }

                session.registerCallback(callback)
                registeredCallbacks[session] = callback
            }
        }

        currentController = activeControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: activeControllers.firstOrNull()

        currentController?.let {
            updateMusicData(it)
        }
    }

    private fun updateMusicData(controller: MediaController) {

        val metadata = controller.metadata
        val playback = controller.playbackState

        val newData = MusicData(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),

            duration = metadata?.getLong(
                MediaMetadata.METADATA_KEY_DURATION
            ) ?: 0L,

            position = playback?.position ?: 0L,

            playbackState = playback?.state
                ?: PlaybackState.STATE_NONE,

            artwork = metadata?.getBitmap(
                MediaMetadata.METADATA_KEY_ART
            ) ?: metadata?.getBitmap(
                MediaMetadata.METADATA_KEY_ALBUM_ART
            )
        )

        if (newData != currentMusicData) {

            currentMusicData = newData

            Log.d(TAG, "MUSIC DATA UPDATED")

            musicDataStore.updateIncoming(newData)

            // Allow artwork time to load before checking it.
            musicDataStore.getArtworkCheckDelayRemaining()?.let { delay ->
                handler.removeCallbacks(artworkCheckRunnable)
                handler.postDelayed(artworkCheckRunnable, delay)
            }

            // Ask TransmissionManager to process the updated snapshot.
            transmissionManager.onUpdateAvailable()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()

        cleanupSessions()

        Log.d(TAG, "LISTENER DISCONNECTED!")
    }

    private fun cleanupSessions() {

        for ((controller, callback) in registeredCallbacks) {
            controller.unregisterCallback(callback)
        }

        registeredCallbacks.clear()
        currentController = null
        currentMusicData = null

        handler.removeCallbacks(artworkCheckRunnable)

        if (::mediaSessionManager.isInitialized) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(
                sessionListener
            )
        }
    }
}

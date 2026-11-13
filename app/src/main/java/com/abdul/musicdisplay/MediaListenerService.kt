
package com.abdul.musicdisplay

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import android.os.Handler
import android.os.Looper

class MediaListenerService : NotificationListenerService() {

    private val musicDataStore = MusicDataStore()

    private lateinit var transmissionManager: TransmissionManager

    private val dryRunHandler = Handler(Looper.getMainLooper())

    private val artworkCheckRunnable = Runnable {
        transmissionManager.onUpdateAvailable()
    }

    private lateinit var mediaSessionManager: MediaSessionManager

    // Store the callbacks registered to each media session
    private val registeredCallbacks =
        mutableMapOf<MediaController, MediaController.Callback>()

    // The session currently being monitored
    private var currentController: MediaController? = null

    // The latest music information
    var currentMusicData: MusicData? = null
        private set

    // Android calls this when active media sessions change
    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->

            Log.d("MusicDisplay", "ACTIVE SESSIONS CHANGED!")

            updateSessions(controllers)
        }

    override fun onListenerConnected() {
        super.onListenerConnected()

        Log.d("MusicDisplay", "LISTENER CONNECTED!")

        mediaSessionManager =
            getSystemService(MediaSessionManager::class.java)

        initializeTransmissionDryRun()

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

    private fun updateSessions(controllers: List<MediaController>?) {

        val activeControllers = controllers.orEmpty()

        // Remove callbacks for sessions that are no longer active
        val removedControllers = registeredCallbacks.keys.filter { oldController ->
            activeControllers.none { it.sessionToken == oldController.sessionToken }
        }

        for (controller in removedControllers) {
            val callback = registeredCallbacks.remove(controller)
            if (callback != null) {
                controller.unregisterCallback(callback)
            }
        }

        // Register callbacks only for newly discovered sessions
        for (session in activeControllers) {

            if (!registeredCallbacks.containsKey(session)) {

                Log.d("MusicDisplay", "Tracking: ${session.packageName}")

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

        // Prefer a session that is currently playing
        currentController = activeControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: activeControllers.firstOrNull()

        // Read its current information immediately
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

        // Only update our stored object if its contents changed
        if (newData != currentMusicData) {

            currentMusicData = newData

            Log.d("MusicDisplay", "MUSIC DATA UPDATED")
            /*Log.d("MusicDisplay", "Title: ${newData.title}")
            Log.d("MusicDisplay", "Artist: ${newData.artist}")
            Log.d("MusicDisplay", "Album: ${newData.album}")
            Log.d("MusicDisplay", "Duration: ${newData.duration} ms")
            Log.d("MusicDisplay", "Position: ${newData.position} ms")
            Log.d("MusicDisplay", "Playback state: ${newData.playbackState}")
            Log.d("MusicDisplay", "Artwork available: ${newData.artwork != null}")

             */
            musicDataStore.updateIncoming(newData)

            // Schedule an artwork check if metadata recently changed
            musicDataStore.getArtworkCheckDelayRemaining()?.let { delay ->
                dryRunHandler.removeCallbacks(artworkCheckRunnable)
                dryRunHandler.postDelayed(artworkCheckRunnable, delay)
            }

            // Process metadata, playback and position immediately
            transmissionManager.onUpdateAvailable()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()

        cleanupSessions()

        Log.d("MusicDisplay", "LISTENER DISCONNECTED!")
    }

    private fun cleanupSessions() {

        for ((controller, callback) in registeredCallbacks) {
            controller.unregisterCallback(callback)
        }

        registeredCallbacks.clear()
        currentController = null
        currentMusicData = null

        if (::mediaSessionManager.isInitialized) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(
                sessionListener
            )
        }
    }

    private fun initializeTransmissionDryRun() {

        transmissionManager = TransmissionManager(
            dataStore = musicDataStore,

            sendBlePacket = { bytes ->

                val result = PacketProtocol.decode(bytes)

                if (result is PacketProtocol.DecodeResult.Success) {

                    val packet = result.packet

                    Log.d(
                        "MusicDryRun",
                        "WOULD SEND: ${packet.type}, " +
                                "Sequence: ${packet.sequence}, " +
                                "Payload: ${packet.payload.size} bytes"
                    )

                    // Simulate an ESP32 acknowledgement.
                    val simulatedAck = PacketProtocol.encode(
                        type = PacketProtocol.MessageType.ACK,
                        sequence = packet.sequence,
                        payload = byteArrayOf()
                    )

                    dryRunHandler.postDelayed({
                        transmissionManager.onPacketReceived(simulatedAck)
                    }, 50)

                    true

                } else {
                    Log.e("MusicDryRun", "Outgoing packet failed decoding!")
                    false
                }
            },

            // Simulate a negotiated MTU of 247.
            // 247 - 3 BLE bytes = 244 bytes per write.
            maxWriteSize = { 244 },

            onError = { error ->
                Log.e("MusicDryRun", "Transmission error: $error")
            }
        )

        // Simulate the BLE connection becoming ready.
        transmissionManager.onConnected()

        Log.d("MusicDryRun", "Transmission dry-run initialized.")
    }
}

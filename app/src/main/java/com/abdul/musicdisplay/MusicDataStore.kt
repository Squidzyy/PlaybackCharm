package com.abdul.musicdisplay

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log

data class MusicChanges(
    val metadataChanged: Boolean,
    val playbackChanged: Boolean,
    val positionChanged: Boolean,
    val artworkChanged: Boolean
) {
    val hasChanges: Boolean
        get() = metadataChanged ||
                playbackChanged ||
                positionChanged ||
                artworkChanged
}

data class PendingMusicUpdate(
    val snapshot: MusicData,
    val changes: MusicChanges
)

class MusicDataStore {

    // Latest information received from Android
    private var incomingMusicData: MusicData? = null

    // Last information successfully transmitted to the ESP32
    private var transmittedMusicData: MusicData? = null

    // Position information used to detect seeking
    private var lastReportedPosition: Long = 0L
    private var lastPositionTimestamp: Long = 0L

    // Tolerance for small timing differences (milliseconds)
    private val seekTolerance = 2000L

    private val artworkDelay = 1000L
    private var metadataChangeTimestamp = 0L
    private var artworkCheckPending = false

    @Synchronized
    fun updateIncoming(newData: MusicData) {

        val previous = incomingMusicData

        val metadataChanged =
            previous == null ||
                    newData.title != previous.title ||
                    newData.artist != previous.artist ||
                    newData.album != previous.album ||
                    newData.duration != previous.duration

        if (metadataChanged) {
            metadataChangeTimestamp = SystemClock.elapsedRealtime()
            artworkCheckPending = true
        }

        // Check whether playback position has unexpectedly jumped
        if (previous != null &&
            newData.playbackState == previous.playbackState &&
            newData.playbackState == 3
        ) {

            val elapsedTime =
                SystemClock.elapsedRealtime() - lastPositionTimestamp

            val expectedPosition =
                lastReportedPosition + elapsedTime

            val difference =
                kotlin.math.abs(newData.position - expectedPosition)

            if (difference > seekTolerance) {
                positionNeedsUpdate = true
            }
        }

        // A new track or playback transition requires position synchronization
        if (previous == null ||
            newData.title != previous.title ||
            newData.artist != previous.artist ||
            newData.playbackState != previous.playbackState
        ) {
            positionNeedsUpdate = true
        }

        incomingMusicData = newData

        // Record the latest position and time
        lastReportedPosition = newData.position
        lastPositionTimestamp = SystemClock.elapsedRealtime()
    }

    private var positionNeedsUpdate = true

    @Synchronized
    fun getPendingUpdate(): PendingMusicUpdate? {

        val incoming = incomingMusicData ?: return null
        val transmitted = transmittedMusicData

        val artworkReady =
            !artworkCheckPending ||
                    SystemClock.elapsedRealtime() - metadataChangeTimestamp >= artworkDelay

        if (artworkCheckPending && artworkReady) {
            artworkCheckPending = false
        }

        val changes = MusicChanges(

            metadataChanged =
                transmitted == null ||
                        incoming.title != transmitted.title ||
                        incoming.artist != transmitted.artist ||
                        incoming.album != transmitted.album ||
                        incoming.duration != transmitted.duration,

            playbackChanged =
                transmitted == null ||
                        incoming.playbackState != transmitted.playbackState,

            positionChanged = positionNeedsUpdate,

            artworkChanged =
                artworkReady &&
                        (transmitted == null ||
                                !sameArtwork(incoming.artwork, transmitted.artwork))
        )

        if (!changes.hasChanges) {
            return null
        }

        return PendingMusicUpdate(
            snapshot = incoming,
            changes = changes
        )
    }

    // Call after the ESP32 confirms successful transmission
    @Synchronized
    fun acknowledgeTransmission(snapshot: MusicData) {

        transmittedMusicData = snapshot

        // Position has now been synchronized
        positionNeedsUpdate = false
    }

    // Force a complete synchronization after reconnecting
    @Synchronized
    fun resetTransmissionState() {

        transmittedMusicData = null
        positionNeedsUpdate = true
    }

    private fun sameArtwork(first: Bitmap?, second: Bitmap?): Boolean {

        if (first === second) return true

        if (first == null || second == null) return false

        if (first.width != second.width ||
            first.height != second.height) {
            return false
        }

        return first.sameAs(second)
    }

    @Synchronized
    fun getArtworkCheckDelayRemaining(): Long? {
        if (!artworkCheckPending) return null

        val elapsed = SystemClock.elapsedRealtime() - metadataChangeTimestamp

        return (artworkDelay - elapsed).coerceAtLeast(0L)
    }
}

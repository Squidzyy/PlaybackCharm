
package com.abdul.musicdisplay

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs

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

    // Latest information received from Android.
    private var incomingMusicData: MusicData? = null

    // Last metadata/playback snapshot successfully transmitted.
    private var transmittedMusicData: MusicData? = null

    // Artwork is tracked independently from the other music data.
    private var transmittedArtwork: Bitmap? = null
    private var artworkCheckPending = false

    private var metadataChangeTimestamp = 0L

    private val artworkDelay = 1000L
    private val artworkRetryInterval = 1000L

    // Position information used to detect seeking.
    private var lastReportedPosition = 0L
    private var lastPositionTimestamp = 0L
    private var positionNeedsUpdate = true

    private val seekTolerance = 2000L

    @Synchronized
    fun updateIncoming(newData: MusicData) {

        val previous = incomingMusicData

        val metadataChanged =
            previous == null ||
                    newData.title != previous.title ||
                    newData.artist != previous.artist ||
                    newData.album != previous.album ||
                    newData.duration != previous.duration

        // Preserve the previous bitmap if Android temporarily returns
        // null for the same track. Never carry old artwork to a new track.
        val effectiveArtwork = when {
            newData.artwork != null -> newData.artwork
            !metadataChanged -> previous?.artwork
            else -> null
        }

        val normalizedData = newData.copy(
            artwork = effectiveArtwork
        )

        if (metadataChanged) {
            metadataChangeTimestamp = SystemClock.elapsedRealtime()
            artworkCheckPending = true
        }

        // Detect a genuinely different image for the same track.
        val artworkChanged =
            previous != null &&
                    !metadataChanged &&
                    normalizedData.artwork != null &&
                    !sameArtwork(previous.artwork, normalizedData.artwork)

        if (artworkChanged) {
            artworkCheckPending = true
            metadataChangeTimestamp = SystemClock.elapsedRealtime()
        }

        // Check whether playback position has unexpectedly jumped.
        if (previous != null &&
            normalizedData.playbackState == previous.playbackState &&
            normalizedData.playbackState == 3
        ) {
            val elapsedTime =
                SystemClock.elapsedRealtime() - lastPositionTimestamp

            val expectedPosition =
                lastReportedPosition + elapsedTime

            val difference =
                abs(normalizedData.position - expectedPosition)

            if (difference > seekTolerance) {
                positionNeedsUpdate = true
            }
        }

        // A new track or playback transition requires position synchronization.
        if (metadataChanged ||
            previous == null ||
            normalizedData.playbackState != previous.playbackState
        ) {
            positionNeedsUpdate = true
        }

        incomingMusicData = normalizedData

        lastReportedPosition = normalizedData.position
        lastPositionTimestamp = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun getPendingUpdate(): PendingMusicUpdate? {

        val incoming = incomingMusicData ?: return null
        val transmitted = transmittedMusicData

        // If the current track already uses the delivered artwork,
        // there is no reason to upload it again.
        if (artworkCheckPending &&
            incoming.artwork != null &&
            sameArtwork(incoming.artwork, transmittedArtwork)
        ) {
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

            // Artwork can only be sent when explicitly pending.
            // Position/playback changes cannot trigger this.
            artworkChanged =
                artworkCheckPending &&
                        incoming.artwork != null &&
                        !sameArtwork(incoming.artwork, transmittedArtwork)
        )

        if (!changes.hasChanges) {
            return null
        }

        return PendingMusicUpdate(
            snapshot = incoming,
            changes = changes
        )
    }

    // Called only after the ESP32 confirms successful transmission.
    @Synchronized
    fun acknowledgeTransmission(
        snapshot: MusicData,
        changes: MusicChanges
    ) {

        transmittedMusicData = snapshot

        // Only an acknowledged artwork batch can update this value.
        if (changes.artworkChanged) {

            transmittedArtwork = snapshot.artwork

            val latestArtwork = incomingMusicData?.artwork

            // If the current image is the one we just delivered,
            // artwork is no longer pending.
            if (latestArtwork != null &&
                sameArtwork(latestArtwork, snapshot.artwork)
            ) {
                artworkCheckPending = false
            }
        }

        // Don't clear a newer position update that arrived while
        // the previous snapshot was being transmitted.
        val latest = incomingMusicData

        if (latest != null &&
            latest.position == snapshot.position &&
            latest.playbackState == snapshot.playbackState
        ) {
            positionNeedsUpdate = false
        }
    }

    // Force a complete synchronization after reconnecting.
    @Synchronized
    fun resetTransmissionState() {

        transmittedMusicData = null
        transmittedArtwork = null

        positionNeedsUpdate = true

        // Re-send artwork after reconnecting if we have a current track.
        artworkCheckPending = incomingMusicData != null
    }

    private fun sameArtwork(
        first: Bitmap?,
        second: Bitmap?
    ): Boolean {

        if (first === second) return true

        if (first == null || second == null) return false

        if (first.width != second.width ||
            first.height != second.height
        ) {
            return false
        }

        return first.sameAs(second)
    }

    @Synchronized
    fun getArtworkCheckDelayRemaining(): Long? {

        if (!artworkCheckPending) return null

        // Artwork is already available; TransmissionManager handles
        // delivery and retrying from this point.
        if (incomingMusicData?.artwork != null) {
            return null
        }

        val elapsed =
            SystemClock.elapsedRealtime() - metadataChangeTimestamp

        if (elapsed < artworkDelay) {
            return artworkDelay - elapsed
        }

        // Artwork still unavailable. Ask Android again in one second.
        return artworkRetryInterval
    }
}

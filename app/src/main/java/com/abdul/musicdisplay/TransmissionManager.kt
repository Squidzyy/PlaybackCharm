
package com.abdul.musicdisplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.zip.CRC32

class TransmissionManager(
    private val dataStore: MusicDataStore,
    private val sendBlePacket: (ByteArray) -> Boolean,
    private val maxWriteSize: () -> Int,
    private val onError: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "MusicTransmission"
        private const val ACK_TIMEOUT = 3000L
        private const val MAX_RETRIES = 3
        private const val ARTWORK_SIZE = 150
        private const val JPEG_QUALITY = 85
    }

    private data class QueuedPacket(
        val sequence: Int,
        val bytes: ByteArray
    )

    private val handler = Handler(Looper.getMainLooper())

    private val packetQueue = ArrayDeque<QueuedPacket>()

    private var nextSequence = 0
    private var currentPacket: QueuedPacket? = null
    private var currentBatch: PendingMusicUpdate? = null

    private var retryCount = 0
    private var connected = false
    private var transmitting = false

    private var timeoutRunnable: Runnable? = null

    // Called when BLE connection becomes READY.
    fun onConnected() {
        handler.post {
            connected = true

            // Force a complete synchronization with the ESP32.
            dataStore.resetTransmissionState()

            Log.d(TAG, "Connected. Starting synchronization.")
            processPendingUpdate()
        }
    }

    // Called when BLE disconnects.
    fun onDisconnected() {
        handler.post {
            connected = false

            cancelTimeout()

            packetQueue.clear()
            currentPacket = null
            currentBatch = null

            retryCount = 0
            transmitting = false

            // The next connection must receive a full update.
            dataStore.resetTransmissionState()

            Log.d(TAG, "Disconnected. Transmission stopped.")
        }
    }

    // Call whenever MusicDataStore receives new music data.
    fun onUpdateAvailable() {
        handler.post {
            processPendingUpdate()
        }
    }

    private fun processPendingUpdate() {
        if (!connected || transmitting) return

        val pending = dataStore.getPendingUpdate() ?: return

        try {
            currentBatch = pending
            packetQueue.clear()

            buildPacketQueue(pending)

            if (packetQueue.isEmpty()) {
                finishBatch()
                return
            }

            transmitting = true

            Log.d(TAG, "Starting batch with ${packetQueue.size} packets.")

            sendNextPacket()

        } catch (exception: Exception) {
            failBatch("Could not prepare transmission: ${exception.message}")
        }
    }

    private fun buildPacketQueue(pending: PendingMusicUpdate) {
        val snapshot = pending.snapshot
        val changes = pending.changes

        // 1. Metadata
        if (changes.metadataChanged) {
            addPacket(
                PacketProtocol.MessageType.METADATA,
                PacketProtocol.encodeMetadata(snapshot)
            )
        }

        // 2. Playback state
        if (changes.playbackChanged) {
            addPacket(
                PacketProtocol.MessageType.PLAYBACK,
                PacketProtocol.encodePlayback(snapshot.playbackState)
            )
        }

        // 3. Playback position
        if (changes.positionChanged) {
            addPacket(
                PacketProtocol.MessageType.POSITION,
                PacketProtocol.encodePosition(snapshot.position)
            )
        }

        // 4. Artwork
        if (changes.artworkChanged) {
            buildArtworkPackets(snapshot.artwork)
        }
    }

    private fun addPacket(
        type: PacketProtocol.MessageType,
        payload: ByteArray
    ) {
        val sequence = nextSequence

        val encoded = PacketProtocol.encode(
            type = type,
            sequence = sequence,
            payload = payload
        )

        if (encoded.size > maxWriteSize()) {
            throw IllegalStateException(
                "$type packet is ${encoded.size} bytes, " +
                        "but BLE allows only ${maxWriteSize()} bytes."
            )
        }

        packetQueue.add(
            QueuedPacket(sequence, encoded)
        )

        nextSequence = (nextSequence + 1) and 0xFFFF
    }

    private fun buildArtworkPackets(artwork: Bitmap?) {
        val jpegBytes: ByteArray
        val width: Int
        val height: Int

        if (artwork == null) {
            // An empty artwork transfer tells the ESP32 to clear its image.
            jpegBytes = byteArrayOf()
            width = 0
            height = 0

        } else {
            val scaled = Bitmap.createScaledBitmap(
                artwork,
                ARTWORK_SIZE,
                ARTWORK_SIZE,
                true
            )

            try {
                val output = ByteArrayOutputStream()

                val compressed = scaled.compress(
                    Bitmap.CompressFormat.JPEG,
                    JPEG_QUALITY,
                    output
                )

                if (!compressed) {
                    throw IllegalStateException("JPEG compression failed.")
                }

                jpegBytes = output.toByteArray()

            } finally {
                // Do not recycle the original bitmap owned by MusicData.
                if (scaled !== artwork) {
                    scaled.recycle()
                }
            }

            width = ARTWORK_SIZE
            height = ARTWORK_SIZE
        }

        // The chunk itself needs 2 bytes for its index.
        val chunkDataSize = maxWriteSize() -
                PacketProtocol.HEADER_SIZE - 2

        if (chunkDataSize <= 0) {
            throw IllegalStateException("BLE MTU is too small for artwork.")
        }

        val chunkCount = if (jpegBytes.isEmpty()) {
            0
        } else {
            (jpegBytes.size + chunkDataSize - 1) / chunkDataSize
        }

        require(chunkCount <= 65535) {
            "Artwork requires too many chunks."
        }

        val checksum = CRC32().apply {
            update(jpegBytes)
        }.value

        // ARTWORK_START
        addPacket(
            PacketProtocol.MessageType.ARTWORK_START,
            PacketProtocol.encodeArtworkStart(
                width = width,
                height = height,
                totalBytes = jpegBytes.size,
                chunkCount = chunkCount
            )
        )

        // ARTWORK_CHUNK packets
        for (index in 0 until chunkCount) {
            val start = index * chunkDataSize
            val end = minOf(start + chunkDataSize, jpegBytes.size)

            val chunk = jpegBytes.copyOfRange(start, end)

            addPacket(
                PacketProtocol.MessageType.ARTWORK_CHUNK,
                PacketProtocol.encodeArtworkChunk(index, chunk)
            )
        }

        // ARTWORK_END
        addPacket(
            PacketProtocol.MessageType.ARTWORK_END,
            PacketProtocol.encodeArtworkEnd(
                totalBytes = jpegBytes.size,
                checksum = checksum
            )
        )
    }

    private fun sendNextPacket() {
        if (!connected) return

        val next = packetQueue.peekFirst()

        if (next == null) {
            finishBatch()
            return
        }

        currentPacket = next
        retryCount = 0

        sendCurrentPacket()
    }

    private fun sendCurrentPacket() {
        val packet = currentPacket ?: return

        val accepted = sendBlePacket(packet.bytes)

        if (!accepted) {
            Log.w(TAG, "BLE write could not start. Retrying.")
            retryCurrentPacket()
            return
        }

        Log.d(TAG, "Sent packet sequence ${packet.sequence}")

        startAckTimeout()
    }

    // Called with bytes received from the ESP32.
    fun onPacketReceived(data: ByteArray) {
        handler.post {
            handleIncomingPacket(data)
        }
    }

    private fun handleIncomingPacket(data: ByteArray) {
        val result = PacketProtocol.decode(data)

        if (result !is PacketProtocol.DecodeResult.Success) {
            Log.w(TAG, "Received invalid packet from ESP32.")
            return
        }

        val response = result.packet

        when (response.type) {
            PacketProtocol.MessageType.ACK -> {
                handleAcknowledgement(response.sequence)
            }

            PacketProtocol.MessageType.NACK -> {
                if (response.sequence == currentPacket?.sequence) {
                    Log.w(TAG, "Received NACK for ${response.sequence}")
                    retryCurrentPacket()
                }
            }

            else -> {
                Log.d(TAG, "Received ${response.type} from ESP32.")
            }
        }
    }

    private fun handleAcknowledgement(sequence: Int) {
        val packet = currentPacket ?: return

        // Ignore acknowledgements for packets other than the one we await.
        if (sequence != packet.sequence) return

        Log.d(TAG, "ACK received for sequence $sequence")

        cancelTimeout()

        packetQueue.pollFirst()

        currentPacket = null
        retryCount = 0

        sendNextPacket()
    }

    private fun startAckTimeout() {
        cancelTimeout()

        val timeout = Runnable {
            Log.w(TAG, "ACK timeout for packet ${currentPacket?.sequence}")
            retryCurrentPacket()
        }

        timeoutRunnable = timeout
        handler.postDelayed(timeout, ACK_TIMEOUT)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let {
            handler.removeCallbacks(it)
        }

        timeoutRunnable = null
    }

    private fun retryCurrentPacket() {
        cancelTimeout()

        val packet = currentPacket ?: return

        if (retryCount >= MAX_RETRIES) {
            failBatch(
                "Packet ${packet.sequence} failed after $MAX_RETRIES retries."
            )
            return
        }

        retryCount++

        Log.w(TAG, "Retrying packet ${packet.sequence}: attempt $retryCount")

        // Give the BLE stack a short moment before trying again.
        handler.postDelayed({
            if (connected && currentPacket?.sequence == packet.sequence) {
                sendCurrentPacket()
            }
        }, 200L)
    }

    private fun finishBatch() {
        cancelTimeout()

        val completedBatch = currentBatch

        if (completedBatch != null) {
            dataStore.acknowledgeTransmission(completedBatch.snapshot)
        }

        Log.d(TAG, "Transmission batch completed.")

        packetQueue.clear()
        currentPacket = null
        currentBatch = null

        transmitting = false
        retryCount = 0

        // Check whether newer music data arrived during this batch.
        processPendingUpdate()
    }

    private fun failBatch(reason: String) {
        cancelTimeout()

        Log.e(TAG, reason)

        packetQueue.clear()
        currentPacket = null
        currentBatch = null

        transmitting = false
        retryCount = 0

        // Do not acknowledge the snapshot. The datastore retains the update.
        onError(reason)
    }
}

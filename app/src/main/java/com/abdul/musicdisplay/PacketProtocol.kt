
package com.abdul.musicdisplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketProtocol {

    const val VERSION = 1

    const val HEADER_SIZE = 8

    enum class MessageType(val id: Int) {
        HELLO(0x01),
        SYNC_REQUEST(0x02),

        METADATA(0x10),
        PLAYBACK(0x11),
        POSITION(0x12),

        ARTWORK_START(0x20),
        ARTWORK_CHUNK(0x21),
        ARTWORK_END(0x22),

        ACK(0x80),
        NACK(0x81)
    }

    data class Packet(
        val type: MessageType,
        val sequence: Int,
        val payload: ByteArray
    )


    sealed class DecodeResult {
        data class Success(val packet: Packet) : DecodeResult()
        data class Error(val reason: String) : DecodeResult()
    }

    fun decode(data: ByteArray): DecodeResult {

        if (data.size < HEADER_SIZE) {
            return DecodeResult.Error("Packet shorter than header")
        }

        val buffer = ByteBuffer.wrap(data)
            .order(ByteOrder.LITTLE_ENDIAN)

        val version = buffer.get().toInt() and 0xFF
        val typeId = buffer.get().toInt() and 0xFF
        val sequence = buffer.short.toInt() and 0xFFFF
        val payloadLength = buffer.short.toInt() and 0xFFFF
        val receivedCrc = buffer.short.toInt() and 0xFFFF

        if (version != VERSION) {
            return DecodeResult.Error("Unsupported protocol version: $version")
        }

        val messageType = MessageType.entries
            .firstOrNull { it.id == typeId }
            ?: return DecodeResult.Error("Unknown message type: $typeId")

        if (data.size != HEADER_SIZE + payloadLength) {
            return DecodeResult.Error("Payload length mismatch")
        }

        val headerAndPayload = data.copyOfRange(0, 6) +
                data.copyOfRange(HEADER_SIZE, data.size)

        val calculatedCrc = calculateCrc16(headerAndPayload)

        if (receivedCrc != calculatedCrc) {
            return DecodeResult.Error("CRC validation failed")
        }

        val payload = data.copyOfRange(HEADER_SIZE, data.size)

        return DecodeResult.Success(
            Packet(
                type = messageType,
                sequence = sequence,
                payload = payload
            )
        )
    }


    fun encode(
        type: MessageType,
        sequence: Int,
        payload: ByteArray
    ): ByteArray {

        require(sequence in 0..65535) {
            "Sequence must fit in 2 bytes"
        }

        require(payload.size <= 65535) {
            "Payload is too large"
        }

        // First 6 bytes of the header.
        val header = ByteBuffer.allocate(6)
            .order(ByteOrder.LITTLE_ENDIAN)

        header.put(VERSION.toByte())
        header.put(type.id.toByte())
        header.putShort(sequence.toShort())
        header.putShort(payload.size.toShort())

        val headerBytes = header.array()

        // CRC covers the first 6 header bytes and the payload.
        val crcInput = headerBytes + payload
        val crc = calculateCrc16(crcInput)

        // Assemble the complete packet.
        return ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(headerBytes)
            .putShort(crc.toShort())
            .put(payload)
            .array()
    }

    fun encodeMetadata(data: MusicData): ByteArray {

        val title = encodeString(data.title)
        val artist = encodeString(data.artist)
        val album = encodeString(data.album)

        return ByteBuffer.allocate(
            title.size + artist.size + album.size + 8
        )
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(title)
            .put(artist)
            .put(album)
            .putLong(data.duration)
            .array()
    }

    fun encodePlayback(state: Int): ByteArray {
        require(state in 0..255)

        return byteArrayOf(state.toByte())
    }

    fun encodePosition(position: Long): ByteArray {
        return ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(position)
            .array()
    }

    private fun encodeString(value: String?): ByteArray {

        if (value == null) {
            return byteArrayOf(
                0xFF.toByte(),
                0xFF.toByte()
            )
        }

        val bytes = value.toByteArray(Charsets.UTF_8)

        require(bytes.size < 65535) {
            "String is too long"
        }

        return ByteBuffer.allocate(2 + bytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(bytes.size.toShort())
            .put(bytes)
            .array()
    }

    private fun calculateCrc16(data: ByteArray): Int {

        var crc = 0xFFFF

        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)

            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }

                crc = crc and 0xFFFF
            }
        }

        return crc
    }

    fun encodeArtworkStart(
        width: Int,
        height: Int,
        totalBytes: Int,
        chunkCount: Int
    ): ByteArray {
        return ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(width.toShort())
            .putShort(height.toShort())
            .putInt(totalBytes)
            .putInt(chunkCount)
            .array()
    }

    fun encodeArtworkChunk(
        chunkIndex: Int,
        chunkData: ByteArray
    ): ByteArray {
        return ByteBuffer.allocate(2 + chunkData.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(chunkIndex.toShort())
            .put(chunkData)
            .array()
    }

    fun encodeArtworkEnd(
        totalBytes: Int,
        checksum: Long
    ): ByteArray {
        return ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(totalBytes)
            .putInt(checksum.toInt())
            .array()
    }
}

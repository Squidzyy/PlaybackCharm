
package com.abdul.musicdisplay

import org.junit.Assert.*
import org.junit.Test

class PacketProtocolTest {

    @Test
    fun packetEncodesAndDecodesCorrectly() {

        val originalPayload = byteArrayOf(
            0x41,
            0x42,
            0x43,
            0x44
        )

        val encoded = PacketProtocol.encode(
            type = PacketProtocol.MessageType.METADATA,
            sequence = 42,
            payload = originalPayload
        )

        val result = PacketProtocol.decode(encoded)

        assertTrue(result is PacketProtocol.DecodeResult.Success)

        val decoded =
            (result as PacketProtocol.DecodeResult.Success).packet

        assertEquals(
            PacketProtocol.MessageType.METADATA,
            decoded.type
        )

        assertEquals(42, decoded.sequence)

        assertArrayEquals(
            originalPayload,
            decoded.payload
        )
    }

    @Test
    fun decoderRejectsCorruptedPacket() {

        val encoded = PacketProtocol.encode(
            type = PacketProtocol.MessageType.PLAYBACK,
            sequence = 1,
            payload = byteArrayOf(3)
        )

        // Change the payload without updating the CRC.
        encoded[PacketProtocol.HEADER_SIZE] = 2

        val result = PacketProtocol.decode(encoded)

        assertTrue(result is PacketProtocol.DecodeResult.Error)

        val error =
            (result as PacketProtocol.DecodeResult.Error).reason

        assertEquals("CRC validation failed", error)
    }

    @Test
    fun decoderRejectsIncorrectPayloadLength() {

        val encoded = PacketProtocol.encode(
            type = PacketProtocol.MessageType.POSITION,
            sequence = 2,
            payload = byteArrayOf(1, 2, 3, 4)
        )

        // Remove one byte from the packet.
        val shortened = encoded.copyOf(encoded.size - 1)

        val result = PacketProtocol.decode(shortened)

        assertTrue(result is PacketProtocol.DecodeResult.Error)
        assertEquals(
            "Payload length mismatch",
            (result as PacketProtocol.DecodeResult.Error).reason
        )
    }

    @Test
    fun decoderRejectsIncompleteHeader() {

        val incompletePacket = byteArrayOf(1, 2, 3)

        val result = PacketProtocol.decode(incompletePacket)

        assertTrue(result is PacketProtocol.DecodeResult.Error)
        assertEquals(
            "Packet shorter than header",
            (result as PacketProtocol.DecodeResult.Error).reason
        )
    }
}

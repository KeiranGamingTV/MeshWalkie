package com.keiranhaas.bluetoothwalkie.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MeshPacketType(
    val value: Int
) {
    VOICE_START(1),
    VOICE_DATA(2),
    VOICE_END(3);

    companion object {
        fun fromValue(
            value: Int
        ): MeshPacketType? {
            return entries.firstOrNull {
                it.value == value
            }
        }
    }
}

data class MeshPacket(
    val type: MeshPacketType,
    val ttl: Int,
    val channel: Int,
    val originId: Long,
    val packetId: Long,
    val sessionId: Long,
    val sequence: Int,
    val payload: ByteArray
)

object MeshPacketCodec {

    private const val MAGIC =
        0x4D57

    private const val VERSION =
        1

    private const val HEADER_SIZE =
        36

    private const val MAX_PAYLOAD =
        16 * 1024

    fun encode(
        packet: MeshPacket
    ): ByteArray {

        require(
            packet.payload.size <= MAX_PAYLOAD
        )

        val buffer =
            ByteBuffer.allocate(
                HEADER_SIZE +
                    packet.payload.size
            )
                .order(
                    ByteOrder.BIG_ENDIAN
                )

        buffer.putShort(
            MAGIC.toShort()
        )

        buffer.put(
            VERSION.toByte()
        )

        buffer.put(
            packet.type.value.toByte()
        )

        buffer.put(
            packet.ttl
                .coerceIn(0, 255)
                .toByte()
        )

        buffer.put(
            packet.channel
                .coerceIn(0, 255)
                .toByte()
        )

        buffer.putLong(
            packet.originId
        )

        buffer.putLong(
            packet.packetId
        )

        buffer.putLong(
            packet.sessionId
        )

        buffer.putInt(
            packet.sequence
        )

        buffer.putShort(
            packet.payload.size
                .toShort()
        )

        buffer.put(
            packet.payload
        )

        return buffer.array()
    }

    fun decode(
        bytes: ByteArray
    ): MeshPacket? {

        if (bytes.size < HEADER_SIZE) {
            return null
        }

        return try {

            val buffer =
                ByteBuffer.wrap(bytes)
                    .order(
                        ByteOrder.BIG_ENDIAN
                    )

            val magic =
                buffer.short
                    .toInt() and 0xFFFF

            if (magic != MAGIC) {
                return null
            }

            val version =
                buffer.get()
                    .toInt() and 0xFF

            if (version != VERSION) {
                return null
            }

            val type =
                MeshPacketType.fromValue(
                    buffer.get()
                        .toInt() and 0xFF
                ) ?: return null

            val ttl =
                buffer.get()
                    .toInt() and 0xFF

            val channel =
                buffer.get()
                    .toInt() and 0xFF

            val originId =
                buffer.long

            val packetId =
                buffer.long

            val sessionId =
                buffer.long

            val sequence =
                buffer.int

            val payloadLength =
                buffer.short
                    .toInt() and 0xFFFF

            if (
                payloadLength > MAX_PAYLOAD ||
                payloadLength != buffer.remaining()
            ) {
                return null
            }

            val payload =
                ByteArray(
                    payloadLength
                )

            buffer.get(payload)

            MeshPacket(
                type = type,
                ttl = ttl,
                channel = channel,
                originId = originId,
                packetId = packetId,
                sessionId = sessionId,
                sequence = sequence,
                payload = payload
            )

        } catch (_: Exception) {
            null
        }
    }
}
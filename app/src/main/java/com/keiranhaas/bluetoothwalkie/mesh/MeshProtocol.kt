package com.keiranhaas.bluetoothwalkie.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MeshPacketType(
    val value: Int
) {

    VOICE_START(1),
    VOICE_DATA(2),
    VOICE_END(3),
    NODE_ANNOUNCE(4);

    companion object {

        fun fromValue(
            value: Int
        ): MeshPacketType? {

            return entries
                .firstOrNull {
                    it.value ==
                        value
                }
        }
    }
}

data class MeshPacket(
    val type: MeshPacketType,
    val channel: Int,
    val originId: Long,
    val packetId: Long,
    val sessionId: Long,
    val sequence: Int,
    val payload: ByteArray
)

data class MeshEnvelope(
    val initialTtl: Int,
    val ttl: Int,
    val hops: Int,
    val channel: Int,
    val originId: Long,
    val packetId: Long,
    val roomTag: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray
)

object MeshPacketCodec {

    private const val MAGIC =
        0x4D57

    private const val VERSION =
        2

    private const val ENVELOPE_HEADER_SIZE =
        46

    private const val PLAINTEXT_HEADER_SIZE =
        15

    private const val MAX_PAYLOAD =
        16 * 1024

    private const val MAX_INITIAL_TTL =
        8

    fun seal(
        packet: MeshPacket,
        crypto: RoomCrypto,
        initialTtl: Int
    ): MeshEnvelope {

        require(
            packet.channel ==
                crypto.channel
        )

        require(
            initialTtl in
                1..MAX_INITIAL_TTL
        )

        require(
            packet.payload.size <=
                MAX_PAYLOAD
        )

        val plaintext =
            encodePlaintext(
                packet
            )

        val temporaryEnvelope =
            MeshEnvelope(
                initialTtl =
                    initialTtl,
                ttl =
                    initialTtl,
                hops =
                    0,
                channel =
                    packet.channel,
                originId =
                    packet.originId,
                packetId =
                    packet.packetId,
                roomTag =
                    crypto.roomTag
                        .copyOf(),
                nonce =
                    ByteArray(
                        RoomCrypto.NONCE_SIZE
                    ),
                ciphertext =
                    ByteArray(0)
            )

        val aad =
            createAad(
                temporaryEnvelope
            )

        val encrypted =
            crypto.encrypt(
                plaintext =
                    plaintext,
                aad =
                    aad
            )

        return temporaryEnvelope.copy(
            nonce =
                encrypted.nonce,
            ciphertext =
                encrypted.ciphertext
        )
    }

    fun open(
        envelope: MeshEnvelope,
        crypto: RoomCrypto
    ): MeshPacket? {

        if (
            envelope.channel !=
            crypto.channel
        ) {
            return null
        }

        if (
            !crypto.matchesRoomTag(
                envelope.roomTag
            )
        ) {
            return null
        }

        val plaintext =
            crypto.decrypt(
                nonce =
                    envelope.nonce,
                ciphertext =
                    envelope.ciphertext,
                aad =
                    createAad(
                        envelope
                    )
            )
                ?: return null

        return decodePlaintext(
            envelope =
                envelope,
            plaintext =
                plaintext
        )
    }

    fun relay(
        envelope: MeshEnvelope
    ): MeshEnvelope? {

        if (
            envelope.ttl <= 0
        ) {
            return null
        }

        val newTtl =
            envelope.ttl - 1

        val newHops =
            envelope.hops + 1

        if (
            newTtl + newHops !=
            envelope.initialTtl
        ) {
            return null
        }

        return envelope.copy(
            ttl =
                newTtl,
            hops =
                newHops
        )
    }

    fun encodeEnvelope(
        envelope: MeshEnvelope
    ): ByteArray {

        require(
            envelope.roomTag.size ==
                RoomCrypto.ROOM_TAG_SIZE
        )

        require(
            envelope.nonce.size ==
                RoomCrypto.NONCE_SIZE
        )

        require(
            envelope.ciphertext.size <=
                65_535
        )

        val buffer =
            ByteBuffer.allocate(
                ENVELOPE_HEADER_SIZE +
                    envelope.ciphertext.size
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
            envelope.initialTtl
                .toByte()
        )

        buffer.put(
            envelope.ttl.toByte()
        )

        buffer.put(
            envelope.hops.toByte()
        )

        buffer.put(
            envelope.channel
                .toByte()
        )

        buffer.put(
            0.toByte()
        )

        buffer.putLong(
            envelope.originId
        )

        buffer.putLong(
            envelope.packetId
        )

        buffer.put(
            envelope.roomTag
        )

        buffer.put(
            envelope.nonce
        )

        buffer.putShort(
            envelope.ciphertext
                .size
                .toShort()
        )

        buffer.put(
            envelope.ciphertext
        )

        return buffer.array()
    }

    fun decodeEnvelope(
        bytes: ByteArray
    ): MeshEnvelope? {

        if (
            bytes.size <
            ENVELOPE_HEADER_SIZE
        ) {
            return null
        }

        return try {

            val buffer =
                ByteBuffer.wrap(
                    bytes
                )
                    .order(
                        ByteOrder.BIG_ENDIAN
                    )

            val magic =
                buffer.short
                    .toInt() and
                    0xFFFF

            if (
                magic != MAGIC
            ) {
                return null
            }

            val version =
                buffer.get()
                    .toInt() and
                    0xFF

            if (
                version != VERSION
            ) {
                return null
            }

            val initialTtl =
                buffer.get()
                    .toInt() and
                    0xFF

            val ttl =
                buffer.get()
                    .toInt() and
                    0xFF

            val hops =
                buffer.get()
                    .toInt() and
                    0xFF

            val channel =
                buffer.get()
                    .toInt() and
                    0xFF

            buffer.get()

            if (
                initialTtl !in
                    1..MAX_INITIAL_TTL
            ) {
                return null
            }

            if (
                ttl + hops !=
                initialTtl
            ) {
                return null
            }

            val originId =
                buffer.long

            val packetId =
                buffer.long

            val roomTag =
                ByteArray(
                    RoomCrypto.ROOM_TAG_SIZE
                )

            buffer.get(
                roomTag
            )

            val nonce =
                ByteArray(
                    RoomCrypto.NONCE_SIZE
                )

            buffer.get(
                nonce
            )

            val encryptedLength =
                buffer.short
                    .toInt() and
                    0xFFFF

            if (
                encryptedLength !=
                buffer.remaining()
            ) {
                return null
            }

            if (
                encryptedLength <
                16
            ) {
                return null
            }

            val ciphertext =
                ByteArray(
                    encryptedLength
                )

            buffer.get(
                ciphertext
            )

            MeshEnvelope(
                initialTtl =
                    initialTtl,
                ttl =
                    ttl,
                hops =
                    hops,
                channel =
                    channel,
                originId =
                    originId,
                packetId =
                    packetId,
                roomTag =
                    roomTag,
                nonce =
                    nonce,
                ciphertext =
                    ciphertext
            )

        } catch (_: Exception) {
            null
        }
    }

    private fun encodePlaintext(
        packet: MeshPacket
    ): ByteArray {

        val buffer =
            ByteBuffer.allocate(
                PLAINTEXT_HEADER_SIZE +
                    packet.payload.size
            )
                .order(
                    ByteOrder.BIG_ENDIAN
                )

        buffer.put(
            packet.type
                .value
                .toByte()
        )

        buffer.putLong(
            packet.sessionId
        )

        buffer.putInt(
            packet.sequence
        )

        buffer.putShort(
            packet.payload
                .size
                .toShort()
        )

        buffer.put(
            packet.payload
        )

        return buffer.array()
    }

    private fun decodePlaintext(
        envelope: MeshEnvelope,
        plaintext: ByteArray
    ): MeshPacket? {

        if (
            plaintext.size <
            PLAINTEXT_HEADER_SIZE
        ) {
            return null
        }

        return try {

            val buffer =
                ByteBuffer.wrap(
                    plaintext
                )
                    .order(
                        ByteOrder.BIG_ENDIAN
                    )

            val type =
                MeshPacketType
                    .fromValue(
                        buffer.get()
                            .toInt() and
                            0xFF
                    )
                    ?: return null

            val sessionId =
                buffer.long

            val sequence =
                buffer.int

            val payloadLength =
                buffer.short
                    .toInt() and
                    0xFFFF

            if (
                payloadLength >
                    MAX_PAYLOAD ||
                payloadLength !=
                    buffer.remaining()
            ) {
                return null
            }

            val payload =
                ByteArray(
                    payloadLength
                )

            buffer.get(
                payload
            )

            MeshPacket(
                type =
                    type,
                channel =
                    envelope.channel,
                originId =
                    envelope.originId,
                packetId =
                    envelope.packetId,
                sessionId =
                    sessionId,
                sequence =
                    sequence,
                payload =
                    payload
            )

        } catch (_: Exception) {
            null
        }
    }

    private fun createAad(
        envelope: MeshEnvelope
    ): ByteArray {

        /*
         * TTL and hops are intentionally
         * NOT included because legitimate
         * relay nodes must modify them.
         *
         * initialTtl IS authenticated,
         * which prevents a relay from
         * increasing the overall hop
         * budget without invalidating GCM.
         */

        return ByteBuffer.allocate(
            29
        )
            .order(
                ByteOrder.BIG_ENDIAN
            )
            .putShort(
                MAGIC.toShort()
            )
            .put(
                VERSION.toByte()
            )
            .put(
                envelope.initialTtl
                    .toByte()
            )
            .put(
                envelope.channel
                    .toByte()
            )
            .putLong(
                envelope.originId
            )
            .putLong(
                envelope.packetId
            )
            .put(
                envelope.roomTag
            )
            .array()
    }
}
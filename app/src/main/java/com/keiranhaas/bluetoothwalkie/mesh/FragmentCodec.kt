package com.keiranhaas.bluetoothwalkie.mesh

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

object FragmentCodec {

    private const val MAGIC =
        0x4D46

    private const val VERSION =
        1

    private const val HEADER_SIZE =
        15

    private const val MAX_FRAGMENTS =
        128

    fun fragment(
        fragmentId: Long,
        packet: ByteArray,
        maxWriteSize: Int
    ): List<ByteArray> {

        val payloadSize =
            (maxWriteSize - HEADER_SIZE)
                .coerceAtLeast(1)

        val count =
            ((packet.size + payloadSize - 1) /
                payloadSize)
                .coerceAtLeast(1)

        require(
            count <= MAX_FRAGMENTS
        ) {
            "Packet requires too many BLE fragments"
        }

        val result =
            ArrayList<ByteArray>(
                count
            )

        for (index in 0 until count) {

            val start =
                index * payloadSize

            val end =
                minOf(
                    start + payloadSize,
                    packet.size
                )

            val length =
                end - start

            val buffer =
                ByteBuffer.allocate(
                    HEADER_SIZE + length
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

            buffer.putLong(
                fragmentId
            )

            buffer.putShort(
                index.toShort()
            )

            buffer.putShort(
                count.toShort()
            )

            buffer.put(
                packet,
                start,
                length
            )

            result += buffer.array()
        }

        return result
    }

    fun readHeader(
        frame: ByteArray
    ): FragmentHeader? {

        if (frame.size < HEADER_SIZE) {
            return null
        }

        return try {

            val buffer =
                ByteBuffer.wrap(frame)
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

            val fragmentId =
                buffer.long

            val index =
                buffer.short
                    .toInt() and 0xFFFF

            val count =
                buffer.short
                    .toInt() and 0xFFFF

            if (
                count !in 1..MAX_FRAGMENTS ||
                index !in 0 until count
            ) {
                return null
            }

            FragmentHeader(
                fragmentId = fragmentId,
                index = index,
                count = count,
                payloadOffset = HEADER_SIZE
            )

        } catch (_: Exception) {
            null
        }
    }
}

data class FragmentHeader(
    val fragmentId: Long,
    val index: Int,
    val count: Int,
    val payloadOffset: Int
)

class FragmentReassembler {

    private data class Assembly(
        val createdAt: Long,
        val parts: Array<ByteArray?>,
        var received: Int = 0,
        var totalBytes: Int = 0
    )

    private val assemblies =
        ConcurrentHashMap<String, Assembly>()

    fun accept(
        source: String,
        frame: ByteArray
    ): ByteArray? {

        cleanupExpired()

        val header =
            FragmentCodec.readHeader(frame)
                ?: return null

        val payload =
            frame.copyOfRange(
                header.payloadOffset,
                frame.size
            )

        val key =
            "$source:${header.fragmentId}"

        val assembly =
            assemblies.getOrPut(key) {
                Assembly(
                    createdAt =
                        System.currentTimeMillis(),
                    parts =
                        arrayOfNulls(
                            header.count
                        )
                )
            }

        if (
            assembly.parts.size !=
            header.count
        ) {
            assemblies.remove(key)
            return null
        }

        synchronized(assembly) {

            if (
                assembly.parts[
                    header.index
                ] == null
            ) {
                assembly.parts[
                    header.index
                ] = payload

                assembly.received++

                assembly.totalBytes +=
                    payload.size
            }

            if (
                assembly.totalBytes >
                MAX_PACKET_BYTES
            ) {
                assemblies.remove(key)
                return null
            }

            if (
                assembly.received !=
                assembly.parts.size
            ) {
                return null
            }

            val output =
                ByteArrayOutputStream(
                    assembly.totalBytes
                )

            for (
                part in
                assembly.parts
            ) {
                if (part == null) {
                    return null
                }

                output.write(part)
            }

            assemblies.remove(key)

            return output.toByteArray()
        }
    }

    private fun cleanupExpired() {

        val now =
            System.currentTimeMillis()

        val iterator =
            assemblies.entries.iterator()

        while (
            iterator.hasNext()
        ) {
            val entry =
                iterator.next()

            if (
                now -
                entry.value.createdAt >
                ASSEMBLY_TIMEOUT_MS
            ) {
                iterator.remove()
            }
        }
    }

    companion object {
        private const val MAX_PACKET_BYTES =
            64 * 1024

        private const val ASSEMBLY_TIMEOUT_MS =
            5_000L
    }
}
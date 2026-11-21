package com.keiranhaas.bluetoothwalkie.mesh

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/**
 * One mesh packet, already split into BLE-sized fragments.
 * It is sent whole or dropped whole, never half-sent from the queue.
 */
class OutgoingPacket(
    val fragments: List<ByteArray>,
    val highPriority: Boolean,
    val enqueuedAtMs: Long = System.currentTimeMillis()
)

/**
 * Per-peer outgoing queue.
 *
 *  - Two priorities: control packets (start/end/announce) go first.
 *  - Bounded: when full, the OLDEST packet is discarded, because old voice
 *    is worthless and newer audio matters more.
 */
class PeerSendQueue(
    private val maxNormalPackets: Int = MAX_NORMAL_PACKETS,
    private val maxHighPackets: Int = MAX_HIGH_PACKETS
) {

    private val lock = Any()

    private val high = ArrayDeque<OutgoingPacket>()
    private val normal = ArrayDeque<OutgoingPacket>()

    private val signal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var closed = false

    val depth: Int
        get() = synchronized(lock) {
            high.size + normal.size
        }

    /** Returns how many older packets were discarded to make room. */
    fun offer(packet: OutgoingPacket): Int {

        var dropped = 0

        val accepted = synchronized(lock) {

            if (closed) {
                false
            } else {

                val queue =
                    if (packet.highPriority) high else normal

                val limit =
                    if (packet.highPriority) {
                        maxHighPackets
                    } else {
                        maxNormalPackets
                    }

                while (queue.size >= limit) {
                    queue.removeFirst()
                    dropped++
                }

                queue.addLast(packet)

                true
            }
        }

        if (accepted) {
            signal.trySend(Unit)
        }

        return dropped
    }

    /** Suspends until a packet is available. Returns null once closed. */
    suspend fun take(): OutgoingPacket? {

        while (true) {

            val next = synchronized(lock) {
                high.removeFirstOrNull()
                    ?: normal.removeFirstOrNull()
            }

            if (next != null) {
                return next
            }

            if (closed) {
                return null
            }

            try {
                signal.receive()
            } catch (_: ClosedReceiveChannelException) {
                return null
            }
        }
    }

    fun close() {

        synchronized(lock) {
            closed = true
            high.clear()
            normal.clear()
        }

        signal.close()
    }

    companion object {

        /** About one second of audio at 25 packets per second. */
        const val MAX_NORMAL_PACKETS = 32

        const val MAX_HIGH_PACKETS = 16
    }
}
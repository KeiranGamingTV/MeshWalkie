package com.keiranhaas.bluetoothwalkie.mesh

import android.content.Context
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MeshEngine(
    context: Context,
    private val nodeId: Long,
    private val channelProvider: () -> Int,
    private val callsignProvider: () -> String,
    private val onVoiceFrame: (
        ByteArray
    ) -> Unit,
    private val onIncomingStarted: (
        String
    ) -> Unit,
    private val onIncomingEnded: () -> Unit,
    onPeerCountChanged: (
        Int
    ) -> Unit,
    onStatus: (
        String
    ) -> Unit
) {

    private data class SeenKey(
        val originId: Long,
        val packetId: Long
    )

    private val secureRandom =
        SecureRandom()

    private val packetCounter =
        AtomicLong(
            secureRandom.nextLong()
        )

    private val seenPackets =
        object :
            LinkedHashMap<
                SeenKey,
                Long
            >(
                2048,
                0.75f,
                true
            ) {

            override fun removeEldestEntry(
                eldest:
                    MutableMap.MutableEntry<
                        SeenKey,
                        Long
                    >?
            ): Boolean {
                return size >
                    SEEN_PACKET_LIMIT
            }
        }

    private val sessionCallsigns =
        ConcurrentHashMap<
            Long,
            String
        >()

    private val lastSequences =
        ConcurrentHashMap<
            Long,
            Int
        >()

    private val transport =
        BleMeshTransport(
            context = context,
            onPacketReceived =
                ::handleIncomingBytes,
            onPeerCountChanged =
                onPeerCountChanged,
            onStatus =
                onStatus
        )

    @Volatile
    private var talkingSessionId:
        Long = 0L

    @Volatile
    private var talkingSequence =
        0

    fun isBluetoothAvailable():
        Boolean {
        return transport
            .isBluetoothAvailable
    }

    fun start():
        Boolean {
        return transport.start()
    }

    fun stop() {
        talkingSessionId = 0L
        talkingSequence = 0

        synchronized(
            seenPackets
        ) {
            seenPackets.clear()
        }

        sessionCallsigns.clear()
        lastSequences.clear()

        transport.stop()
    }

    @Synchronized
    fun beginTalking() {

        if (
            talkingSessionId != 0L
        ) {
            return
        }

        var session =
            secureRandom.nextLong()

        if (
            session == 0L
        ) {
            session = 1L
        }

        talkingSessionId =
            session

        talkingSequence =
            0

        sendLocalPacket(
            type =
                MeshPacketType.VOICE_START,
            sessionId =
                session,
            sequence =
                talkingSequence,
            payload =
                callsignProvider()
                    .take(20)
                    .toByteArray(
                        Charsets.UTF_8
                    )
        )
    }

    @Synchronized
    fun sendVoiceFrame(
        compressedAudio:
            ByteArray
    ) {

        val session =
            talkingSessionId

        if (
            session == 0L
        ) {
            return
        }

        talkingSequence++

        sendLocalPacket(
            type =
                MeshPacketType.VOICE_DATA,
            sessionId =
                session,
            sequence =
                talkingSequence,
            payload =
                compressedAudio
        )
    }

    @Synchronized
    fun endTalking() {

        val session =
            talkingSessionId

        if (
            session == 0L
        ) {
            return
        }

        talkingSequence++

        sendLocalPacket(
            type =
                MeshPacketType.VOICE_END,
            sessionId =
                session,
            sequence =
                talkingSequence,
            payload =
                ByteArray(0)
        )

        talkingSessionId =
            0L

        talkingSequence =
            0
    }

    private fun sendLocalPacket(
        type: MeshPacketType,
        sessionId: Long,
        sequence: Int,
        payload: ByteArray
    ) {

        val packet =
            MeshPacket(
                type = type,
                ttl = MAX_HOPS,
                channel =
                    channelProvider()
                        .coerceIn(
                            1,
                            16
                        ),
                originId =
                    nodeId,
                packetId =
                    nextPacketId(),
                sessionId =
                    sessionId,
                sequence =
                    sequence,
                payload =
                    payload
            )

        markSeen(
            packet
        )

        transport.broadcast(
            packet
        )
    }

    private fun handleIncomingBytes(
        bytes: ByteArray
    ) {

        val packet =
            MeshPacketCodec.decode(
                bytes
            )
                ?: return

        if (
            !markSeen(packet)
        ) {
            return
        }

        if (
            packet.originId !=
            nodeId &&
            packet.channel ==
            channelProvider()
        ) {
            handleLocalDelivery(
                packet
            )
        }

        if (
            packet.ttl > 0
        ) {

            transport.broadcast(
                packet.copy(
                    ttl =
                        packet.ttl - 1
                )
            )
        }
    }

    private fun handleLocalDelivery(
        packet: MeshPacket
    ) {

        when (
            packet.type
        ) {

            MeshPacketType.VOICE_START -> {

                val callsign =
                    packet.payload
                        .toString(
                            Charsets.UTF_8
                        )
                        .trim()
                        .take(20)
                        .ifBlank {
                            "Unknown"
                        }

                sessionCallsigns[
                    packet.sessionId
                ] =
                    callsign

                lastSequences[
                    packet.sessionId
                ] =
                    packet.sequence

                onIncomingStarted(
                    callsign
                )
            }

            MeshPacketType.VOICE_DATA -> {

                val previous =
                    lastSequences[
                        packet.sessionId
                    ]

                if (
                    previous != null &&
                    packet.sequence <=
                    previous
                ) {
                    return
                }

                lastSequences[
                    packet.sessionId
                ] =
                    packet.sequence

                val callsign =
                    sessionCallsigns[
                        packet.sessionId
                    ]

                if (
                    callsign != null
                ) {
                    onIncomingStarted(
                        callsign
                    )
                }

                onVoiceFrame(
                    packet.payload
                )
            }

            MeshPacketType.VOICE_END -> {

                sessionCallsigns.remove(
                    packet.sessionId
                )

                lastSequences.remove(
                    packet.sessionId
                )

                onIncomingEnded()
            }
        }
    }

    private fun markSeen(
        packet: MeshPacket
    ): Boolean {

        val key =
            SeenKey(
                originId =
                    packet.originId,
                packetId =
                    packet.packetId
            )

        synchronized(
            seenPackets
        ) {

            if (
                seenPackets.containsKey(
                    key
                )
            ) {
                return false
            }

            seenPackets[
                key
            ] =
                System.currentTimeMillis()

            return true
        }
    }

    private fun nextPacketId():
        Long {

        val value =
            packetCounter
                .incrementAndGet()

        return if (
            value == 0L
        ) {
            packetCounter
                .incrementAndGet()
        } else {
            value
        }
    }

    companion object {

        /*
         * Local device -> neighbor = hop 1.
         *
         * MAX_HOPS 4 allows several
         * relays while still keeping
         * runaway traffic controlled.
         */
        private const val MAX_HOPS =
            4

        private const val SEEN_PACKET_LIMIT =
            4096
    }
}
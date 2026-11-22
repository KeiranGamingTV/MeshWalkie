package com.keiranhaas.bluetoothwalkie.mesh

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MeshEngine(
    context: Context,
    private val nodeId: Long,
    private val onVoiceStart:
        (
            sessionId: Long,
            callsign: String,
            firstSequence: Int
        ) -> Unit,
    private val onVoiceFrame:
        (
            sessionId: Long,
            sequence: Int,
            frame: ByteArray
        ) -> Unit,
    private val onVoiceEnd:
        (
            sessionId: Long,
            endSequence: Int
        ) -> Unit,
    private val onTransmitPreempted:
        (winnerCallsign: String) -> Unit,
    onPeerCountChanged:
        (Int) -> Unit,
    onStatus:
        (String) -> Unit
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

    @Volatile
    private var scope =
        newScope()

    private fun newScope() =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO
        )

    private val seenPackets =
        object :
            LinkedHashMap<
                SeenKey,
                Long
            >(
                4096,
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

    private val arbiter =
        TalkerArbiter()

    private val transport =
        BleMeshTransport(
            context =
                context,

            onPacketReceived = {
                sourceAddress,
                packet ->

                handleIncomingBytes(
                    sourceAddress =
                        sourceAddress,
                    bytes =
                        packet
                )
            },

            onPeerCountChanged =
                onPeerCountChanged,

            onStatus =
                onStatus
        )

    @Volatile
    private var roomCrypto:
        RoomCrypto? =
        null

    @Volatile
    private var currentChannel =
        1

    @Volatile
    private var currentCallsign =
        "Radio"

    @Volatile
    private var running =
        false

    @Volatile
    private var talkingSessionId =
        0L

    @Volatile
    private var talkingSequence =
        0

    private var announceJob:
        Job? =
        null

    private var floorJob:
        Job? =
        null

    fun updateConfig(
        callsign: String,
        channel: Int,
        roomCode: String
    ) {

        currentCallsign =
            callsign
                .trim()
                .take(20)
                .ifBlank {
                    "Radio"
                }

        currentChannel =
            channel.coerceIn(
                1,
                16
            )

        roomCrypto =
            if (
                roomCode.isBlank()
            ) {
                null
            } else {

                try {
                    RoomCrypto.derive(
                        roomCode =
                            roomCode,
                        channel =
                            currentChannel
                    )
                } catch (_: Exception) {
                    null
                }
            }

        sessionCallsigns.clear()

        arbiter.clearRemote()

        if (
            running
        ) {
            sendAnnouncement()
        }
    }

    fun isBluetoothAvailable():
        Boolean {

        return transport
            .isBluetoothAvailable
    }

    fun hasEncryptionKey():
        Boolean {

        return roomCrypto !=
            null
    }

    fun start():
            Boolean {

        if (
            running
        ) {
            return true
        }

        if (
            roomCrypto == null
        ) {
            return false
        }

        scope =
            newScope()

        MeshDiagnostics
            .resetForNewRun()

        transport.localNodeId =
            nodeId

        val started =
            transport.start()

        if (
            !started
        ) {
            return false
        }

        running =
            true

        startAnnouncements()

        startFloorWatchdog()

        return true
    }

    fun stop() {

        running =
            false

        talkingSessionId =
            0L

        talkingSequence =
            0

        announceJob?.cancel()
        announceJob = null

        floorJob?.cancel()
        floorJob = null
        arbiter.clear()

        synchronized(
            seenPackets
        ) {
            seenPackets.clear()
        }

        sessionCallsigns.clear()

        transport.stop()

        scope.cancel()
    }

    @Synchronized
    fun beginTalking(): TalkRequestResult {

        if (!running || roomCrypto == null) {
            return TalkRequestResult.NotRunning
        }

        if (talkingSessionId != 0L) {
            return TalkRequestResult.Granted
        }

        var session =
            secureRandom.nextLong()

        if (session == 0L) {
            session = 1L
        }

        val result =
            arbiter.tryAcquireLocal(session)

        if (result !is TalkRequestResult.Granted) {
            return result
        }

        talkingSessionId = session
        talkingSequence = 0

        sendLocalPacket(
            type = MeshPacketType.VOICE_START,
            sessionId = session,
            sequence = 0,
            payload =
                currentCallsign.toByteArray(Charsets.UTF_8)
        )

        return TalkRequestResult.Granted
    }

    @Synchronized
    fun sendVoiceFrame(
        compressedAudio: ByteArray
    ) {

        val session = talkingSessionId

        if (session == 0L) {
            return
        }

        talkingSequence++

        arbiter.noteLocalSequence(
            session,
            talkingSequence
        )

        sendLocalPacket(
            type = MeshPacketType.VOICE_DATA,
            sessionId = session,
            sequence = talkingSequence,
            payload = compressedAudio
        )
    }

    @Synchronized
    fun endTalking() {

        val session = talkingSessionId

        if (session == 0L) {
            return
        }

        talkingSequence++

        sendLocalPacket(
            type = MeshPacketType.VOICE_END,
            sessionId = session,
            sequence = talkingSequence,
            payload = ByteArray(0)
        )

        talkingSessionId = 0L
        talkingSequence = 0

        arbiter.releaseLocal(session)
    }

    fun sendAnnouncement() {

        if (
            !running
        ) {
            return
        }

        sendLocalPacket(
            type =
                MeshPacketType
                    .NODE_ANNOUNCE,

            sessionId =
                0L,

            sequence =
                0,

            payload =
                currentCallsign
                    .toByteArray(
                        Charsets.UTF_8
                    )
        )
    }

    private fun startAnnouncements() {

        announceJob?.cancel()

        announceJob =
            scope.launch {

                delay(
                    1_000L
                )

                while (
                    isActive &&
                    running
                ) {

                    sendAnnouncement()

                    delay(
                        ANNOUNCE_INTERVAL_MS
                    )
                }
            }
    }

    private fun startFloorWatchdog() {

        floorJob?.cancel()

        floorJob =
            scope.launch {

                while (
                    isActive &&
                    running
                ) {

                    delay(
                        FLOOR_CHECK_INTERVAL_MS
                    )

                    applyFloorUpdate(
                        arbiter.expireStale()
                    )
                }
            }
    }

    private fun sendLocalPacket(
        type: MeshPacketType,
        sessionId: Long,
        sequence: Int,
        payload: ByteArray
    ) {

        val crypto =
            roomCrypto
                ?: return

        val packet =
            MeshPacket(
                type =
                    type,

                channel =
                    currentChannel,

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

        val control =
            type != MeshPacketType.VOICE_DATA

        val envelope =
            MeshPacketCodec.seal(
                packet =
                    packet,
                crypto =
                    crypto,
                initialTtl =
                    INITIAL_TTL,
                flags =
                    if (control) {
                        MeshPacketCodec
                            .FLAG_CONTROL
                    } else {
                        0
                    }
            )

        markSeen(
            envelope
        )

        val encoded =
            MeshPacketCodec
                .encodeEnvelope(
                    envelope
                )

        MeshDiagnostics
            .recordPacketSent()

        transport.broadcast(
            packetId =
                envelope.packetId,
            encodedPacket =
                encoded,
            highPriority =
                control
        )
    }

    private fun handleIncomingBytes(
        sourceAddress: String,
        bytes: ByteArray
    ) {

        val envelope =
            MeshPacketCodec
                .decodeEnvelope(
                    bytes
                )
                ?: return

        /*
         * A directly received packet has
         * envelope.hops == 0, therefore
         * the actual path distance to us
         * is hops + 1.
         */
        val pathHops =
            envelope.hops + 1

        MeshDiagnostics
            .recordPacketReceived(
                pathHops
            )

        if (
            !markSeen(
                envelope
            )
        ) {

            MeshDiagnostics
                .recordDuplicateDropped()

            return
        }

        if (
            envelope.originId ==
            nodeId
        ) {
            return
        }

        val crypto =
            roomCrypto

        if (
            crypto != null &&
            envelope.channel ==
                currentChannel &&
            crypto.matchesRoomTag(
                envelope.roomTag
            )
        ) {

            val packet =
                MeshPacketCodec.open(
                    envelope =
                        envelope,
                    crypto =
                        crypto
                )

            if (
                packet == null
            ) {

                MeshDiagnostics
                    .recordDecryptFailure()

            } else {

                handleLocalPacket(
                    sourceAddress =
                        sourceAddress,
                    envelope =
                        envelope,
                    packet =
                        packet
                )
            }
        }

        val relayed =
            MeshPacketCodec.relay(
                envelope
            )

        if (
            relayed != null
        ) {

            MeshDiagnostics
                .recordPacketRelayed()

            transport.broadcast(
                packetId =
                    relayed.packetId,

                encodedPacket =
                    MeshPacketCodec
                        .encodeEnvelope(
                            relayed
                        ),

                excludeAddress =
                    sourceAddress,

                highPriority =
                    relayed.isControl,

                excludeNodeId =
                    relayed.originId
            )
        }
    }

    private fun handleLocalPacket(
        sourceAddress: String,
        envelope: MeshEnvelope,
        packet: MeshPacket
    ) {

        val pathHops =
            envelope.hops + 1

        when (
            packet.type
        ) {

            MeshPacketType
                .NODE_ANNOUNCE -> {

                val callsign =
                    decodeCallsign(
                        packet.payload
                    )

                MeshDiagnostics
                    .updateTopologyNode(
                        nodeId =
                            packet.originId,
                        callsign =
                            callsign,
                        hops =
                            pathHops
                    )

                if (
                    envelope.hops ==
                    0
                ) {

                    transport
                        .updatePeerIdentity(
                            address =
                                sourceAddress,
                            nodeId =
                                packet.originId,
                            callsign =
                                callsign
                        )
                }
            }

            MeshPacketType.VOICE_START -> {

                val callsign =
                    decodeCallsign(packet.payload)

                if (sessionCallsigns.size > MAX_TRACKED_SESSIONS) {
                    sessionCallsigns.clear()
                }

                sessionCallsigns[packet.sessionId] =
                    callsign

                MeshDiagnostics.updateTopologyNode(
                    nodeId = packet.originId,
                    callsign = callsign,
                    hops = pathHops
                )

                if (envelope.hops == 0) {

                    transport.updatePeerIdentity(
                        address = sourceAddress,
                        nodeId = packet.originId,
                        callsign = callsign
                    )
                }

                applyDecision(
                    arbiter.onRemotePacket(
                        sessionId = packet.sessionId,
                        originId = packet.originId,
                        callsign = callsign,
                        sequence = packet.sequence
                    )
                )
            }

            MeshPacketType.VOICE_DATA -> {

                val callsign =
                    sessionCallsigns[packet.sessionId]

                if (callsign != null) {

                    MeshDiagnostics.updateTopologyNode(
                        nodeId = packet.originId,
                        callsign = callsign,
                        hops = pathHops
                    )
                }

                val decision =
                    arbiter.onRemotePacket(
                        sessionId = packet.sessionId,
                        originId = packet.originId,
                        callsign = callsign,
                        sequence = packet.sequence
                    )

                applyDecision(decision)

                /*
                 * Out-of-order frames are still passed on;
                 * the jitter buffer handles reordering.
                 */
                if (decision.deliver) {

                    onVoiceFrame(
                        packet.sessionId,
                        packet.sequence,
                        packet.payload
                    )
                }
            }

            MeshPacketType.VOICE_END -> {

                sessionCallsigns.remove(
                    packet.sessionId
                )

                applyFloorUpdate(
                    update =
                        arbiter.onRemoteEnd(
                            packet.sessionId
                        ),
                    endSequence =
                        packet.sequence
                )
            }
        }
    }

    private fun applyDecision(
        decision: TalkerArbiter.PacketDecision
    ) {

        decision.preemptedLocal?.let { winner ->

            endTalking()

            onTransmitPreempted(
                winner.callsign
            )
        }

        applyFloorUpdate(
            decision.update
        )
    }

    private fun applyFloorUpdate(
        update: TalkerArbiter.FloorUpdate,
        endSequence: Int? = null
    ) {

        update.ended?.let { ended ->

            onVoiceEnd(
                ended.sessionId,
                endSequence
                    ?: (ended.highestSequence + 1)
            )
        }

        update.started?.let { started ->

            onVoiceStart(
                started.sessionId,
                started.callsign,
                update.startSequence
            )
        }
    }

    private fun decodeCallsign(
        payload: ByteArray
    ): String {

        return payload
            .toString(
                Charsets.UTF_8
            )
            .trim()
            .take(20)
            .ifBlank {
                "Unknown"
            }
    }

    private fun markSeen(
        envelope: MeshEnvelope
    ): Boolean {

        val key =
            SeenKey(
                originId =
                    envelope.originId,

                packetId =
                    envelope.packetId
            )

        synchronized(
            seenPackets
        ) {

            if (
                seenPackets
                    .containsKey(
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
         * Allows several relay stages,
         * while keeping packet flooding
         * bounded.
         */
        private const val INITIAL_TTL =
            4

        private const val SEEN_PACKET_LIMIT =
            8192

        private const val ANNOUNCE_INTERVAL_MS =
            15_000L

        private const val FLOOR_CHECK_INTERVAL_MS =
            250L

        private const val MAX_TRACKED_SESSIONS =
            64
    }
}
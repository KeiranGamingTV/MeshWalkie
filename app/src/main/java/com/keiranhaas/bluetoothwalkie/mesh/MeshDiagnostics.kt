package com.keiranhaas.bluetoothwalkie.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

data class PeerDiagnostic(
    val address: String,
    val connected: Boolean = false,
    val nodeId: Long? = null,
    val callsign: String? = null,
    val mtu: Int = 23,
    val rssi: Int? = null,
    val reconnectAttempts: Int = 0,
    val connectedAtMs: Long? = null,
    val linkQuality: Int? = null,
    val queueDepth: Int = 0,
    val lastSeenMs: Long =
        System.currentTimeMillis()
)

data class TopologyNodeDiagnostic(
    val nodeId: Long,
    val callsign: String,
    val bestHops: Int,
    val lastHops: Int,
    val lastSeenMs: Long
)

data class MeshDiagnosticsState(
    val directPeers:
        List<PeerDiagnostic> =
        emptyList(),

    val topologyNodes:
        List<TopologyNodeDiagnostic> =
        emptyList(),

    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val packetsRelayed: Long = 0,

    val duplicatesDropped: Long = 0,
    val decryptFailures: Long = 0,

    val voiceFramesReceived: Long = 0,
    val voiceFramesLost: Long = 0,

    val lateFramesDropped: Long = 0,

    val congestionDrops: Long = 0,
    val linkResets: Long = 0,

    val lastHopCount: Int = 0,
    val maximumHopCount: Int = 0,
    val averageHopCount: Double = 0.0
) {

    val packetLossPercent:
        Double
        get() {

            val total =
                voiceFramesReceived +
                    voiceFramesLost

            if (
                total <= 0
            ) {
                return 0.0
            }

            return (
                voiceFramesLost
                    .toDouble() /
                    total.toDouble()
                ) * 100.0
        }
}

object MeshDiagnostics {

    private val lock =
        Any()

    private val peers =
        LinkedHashMap<
            String,
            PeerDiagnostic
        >()

    private val nodes =
        LinkedHashMap<
            Long,
            TopologyNodeDiagnostic
        >()

    private var hopSamples =
        0L

    private var totalHops =
        0L

    private val mutableState =
        MutableStateFlow(
            MeshDiagnosticsState()
        )

    val state:
        StateFlow<MeshDiagnosticsState> =
        mutableState.asStateFlow()

    fun resetForNewRun() {

        synchronized(lock) {

            peers.clear()
            nodes.clear()

            hopSamples = 0
            totalHops = 0

            mutableState.value =
                MeshDiagnosticsState()
        }
    }

    fun markPeerConnected(
        address: String,
        mtu: Int,
        rssi: Int?
    ) {

        synchronized(lock) {

            val previous =
                peers[address]

            peers[address] =
                (previous
                    ?: PeerDiagnostic(
                        address = address
                    )
                ).copy(
                    connected = true,
                    mtu = mtu,
                    rssi =
                        rssi
                            ?: previous?.rssi,
                    connectedAtMs =
                        System.currentTimeMillis(),
                    lastSeenMs =
                        System.currentTimeMillis()
                )

            publishPeers()
        }
    }

    fun markPeerDisconnected(
        address: String
    ) {

        synchronized(lock) {

            val previous =
                peers[address]
                    ?: PeerDiagnostic(
                        address =
                            address
                    )

            peers[address] =
                previous.copy(
                    connected = false,
                    lastSeenMs =
                        System.currentTimeMillis()
                )

            publishPeers()
        }
    }

    fun updatePeerLink(
        address: String,
        mtu: Int? = null,
        rssi: Int? = null,
        quality: Int? = null,
        queueDepth: Int? = null,
        nodeId: Long? = null
    ) {

        synchronized(lock) {

            val previous =
                peers[address]
                    ?: PeerDiagnostic(
                        address =
                            address
                    )

            peers[address] =
                previous.copy(
                    mtu =
                        mtu
                            ?: previous.mtu,
                    rssi =
                        rssi
                            ?: previous.rssi,
                    linkQuality =
                        quality
                            ?: previous.linkQuality,
                    queueDepth =
                        queueDepth
                            ?: previous.queueDepth,
                    nodeId =
                        nodeId
                            ?: previous.nodeId,
                    lastSeenMs =
                        System.currentTimeMillis()
                )

            publishPeers()
        }
    }

    fun setPeerIdentity(
        address: String,
        nodeId: Long,
        callsign: String
    ) {

        synchronized(lock) {

            val previous =
                peers[address]
                    ?: PeerDiagnostic(
                        address =
                            address
                    )

            peers[address] =
                previous.copy(
                    nodeId =
                        nodeId,
                    callsign =
                        callsign,
                    lastSeenMs =
                        System.currentTimeMillis()
                )

            publishPeers()
        }
    }

    fun markReconnectAttempt(
        address: String
    ) {

        synchronized(lock) {

            val previous =
                peers[address]
                    ?: PeerDiagnostic(
                        address =
                            address
                    )

            peers[address] =
                previous.copy(
                    reconnectAttempts =
                        previous
                            .reconnectAttempts +
                            1,
                    lastSeenMs =
                        System.currentTimeMillis()
                )

            publishPeers()
        }
    }

    fun updateTopologyNode(
        nodeId: Long,
        callsign: String,
        hops: Int
    ) {

        if (
            hops <= 0
        ) {
            return
        }

        synchronized(lock) {

            val previous =
                nodes[nodeId]

            val bestHops =
                if (
                    previous == null
                ) {
                    hops
                } else {
                    min(
                        previous.bestHops,
                        hops
                    )
                }

            nodes[nodeId] =
                TopologyNodeDiagnostic(
                    nodeId =
                        nodeId,
                    callsign =
                        callsign,
                    bestHops =
                        bestHops,
                    lastHops =
                        hops,
                    lastSeenMs =
                        System.currentTimeMillis()
                )

            mutableState.value =
                mutableState.value.copy(
                    topologyNodes =
                        nodes.values
                            .sortedWith(
                                compareBy<
                                    TopologyNodeDiagnostic
                                > {
                                    it.bestHops
                                }.thenBy {
                                    it.callsign
                                }
                            )
                )
        }
    }

    fun recordPacketSent() {

        synchronized(lock) {
            mutableState.value =
                mutableState.value.copy(
                    packetsSent =
                        mutableState
                            .value
                            .packetsSent +
                            1
                )
        }
    }

    fun recordPacketReceived(
        hops: Int
    ) {

        synchronized(lock) {

            hopSamples++
            totalHops += hops

            val average =
                totalHops.toDouble() /
                    hopSamples.toDouble()

            mutableState.value =
                mutableState.value.copy(
                    packetsReceived =
                        mutableState
                            .value
                            .packetsReceived +
                            1,

                    lastHopCount =
                        hops,

                    maximumHopCount =
                        max(
                            mutableState
                                .value
                                .maximumHopCount,
                            hops
                        ),

                    averageHopCount =
                        average
                )
        }
    }

    fun recordPacketRelayed() {

        synchronized(lock) {
            mutableState.value =
                mutableState.value.copy(
                    packetsRelayed =
                        mutableState
                            .value
                            .packetsRelayed +
                            1
                )
        }
    }

    fun recordDuplicateDropped() {

        synchronized(lock) {
            mutableState.value =
                mutableState.value.copy(
                    duplicatesDropped =
                        mutableState
                            .value
                            .duplicatesDropped +
                            1
                )
        }
    }

    fun recordDecryptFailure() {

        synchronized(lock) {
            mutableState.value =
                mutableState.value.copy(
                    decryptFailures =
                        mutableState
                            .value
                            .decryptFailures +
                            1
                )
        }
    }

    fun recordVoiceFrameReceived() {

        synchronized(lock) {
            mutableState.value =
                mutableState.value.copy(
                    voiceFramesReceived =
                        mutableState
                            .value
                            .voiceFramesReceived +
                            1
                )
        }
    }

    fun recordVoiceFrameLost() {

        synchronized(lock) {
            mutableState.value =
                mutableState.value.copy(
                    voiceFramesLost =
                        mutableState
                            .value
                            .voiceFramesLost +
                            1
                )
        }
    }

    fun recordLateFrameDropped() {

        synchronized(lock) {
            mutableState.value =
                mutableState.value.copy(
                    lateFramesDropped =
                        mutableState
                            .value
                            .lateFramesDropped +
                            1
                )
        }
    }

    fun recordCongestionDrops(
        count: Int
    ) {

        if (
            count <= 0
        ) {
            return
        }

        synchronized(lock) {
            mutableState.value =
                mutableState.value.copy(
                    congestionDrops =
                        mutableState
                            .value
                            .congestionDrops +
                            count
                )
        }
    }

    fun recordLinkReset() {

        synchronized(lock) {
            mutableState.value =
                mutableState.value.copy(
                    linkResets =
                        mutableState
                            .value
                            .linkResets +
                            1
                )
        }
    }

    private fun publishPeers() {

        mutableState.value =
            mutableState.value.copy(
                directPeers =
                    peers.values
                        .sortedWith(
                            compareByDescending<
                                PeerDiagnostic
                            > {
                                it.connected
                            }.thenBy {
                                it.callsign
                                    ?: it.address
                            }
                        )
            )
    }
}
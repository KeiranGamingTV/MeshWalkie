package com.keiranhaas.bluetoothwalkie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keiranhaas.bluetoothwalkie.MeshUiState
import com.keiranhaas.bluetoothwalkie.mesh.MeshDiagnosticsState
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    state: MeshUiState,
    diagnostics:
        MeshDiagnosticsState
) {

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    16.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            )
    ) {

        Text(
            text =
                "Mesh Diagnostics",

            style =
                MaterialTheme
                    .typography
                    .headlineSmall,

            fontWeight =
                FontWeight.Bold
        )

        DiagnosticSection(
            title =
                "Network"
        ) {

            DiagnosticRow(
                "Status",
                if (
                    state.networkRunning
                ) {
                    "Online"
                } else {
                    "Offline"
                }
            )

            DiagnosticRow(
                "Channel",
                state.channel
                    .toString()
            )

            DiagnosticRow(
                "Direct peers",
                state.peerCount
                    .toString()
            )

            DiagnosticRow(
                "Known mesh nodes",
                diagnostics
                    .topologyNodes
                    .size
                    .toString()
            )
        }

        DiagnosticSection(
            title =
                "Packet Statistics"
        ) {

            DiagnosticRow(
                "Packets sent",
                diagnostics
                    .packetsSent
                    .toString()
            )

            DiagnosticRow(
                "Packets received",
                diagnostics
                    .packetsReceived
                    .toString()
            )

            DiagnosticRow(
                "Packets relayed",
                diagnostics
                    .packetsRelayed
                    .toString()
            )

            DiagnosticRow(
                "Duplicates dropped",
                diagnostics
                    .duplicatesDropped
                    .toString()
            )

            DiagnosticRow(
                "Congestion drops",
                diagnostics
                    .congestionDrops
                    .toString()
            )

            DiagnosticRow(
                "Link resets",
                diagnostics
                    .linkResets
                    .toString()
            )

            DiagnosticRow(
                "Last reset reason",
                diagnostics
                    .lastResetReason
            )

            DiagnosticRow(
                "Disconnects",
                diagnostics
                    .disconnects
                    .toString()
            )

            DiagnosticRow(
                "Last disconnect code",
                disconnectLabel(
                    diagnostics
                        .lastDisconnectCode
                )
            )

            DiagnosticRow(
                "Authentication failures",
                diagnostics
                    .decryptFailures
                    .toString()
            )

            DiagnosticRow(
                "Stale/replayed packets",
                diagnostics
                    .stalePacketsDropped
                    .toString()
            )

            DiagnosticRow(
                "Long range (Coded PHY)",
                if (
                    diagnostics
                        .longRangeSupported
                ) {
                    "Supported"
                } else {
                    "Not supported"
                }
            )
        }

        DiagnosticSection(
            title =
                "Voice / Jitter Buffer"
        ) {

            DiagnosticRow(
                "Frames played",
                diagnostics
                    .voiceFramesReceived
                    .toString()
            )

            DiagnosticRow(
                "Lost frames / PLC",
                diagnostics
                    .voiceFramesLost
                    .toString()
            )

            DiagnosticRow(
                "Late frames dropped",
                diagnostics
                    .lateFramesDropped
                    .toString()
            )

            DiagnosticRow(
                "Packet loss",
                String.format(
                    Locale.US,
                    "%.2f%%",
                    diagnostics
                        .packetLossPercent
                )
            )

            DiagnosticRow(
                "Jitter target",
                "${diagnostics.jitterTargetMs} ms"
            )

            DiagnosticRow(
                "Frames trimmed",
                diagnostics
                    .framesTrimmed
                    .toString()
            )

            DiagnosticRow(
                "Audio frame",
                "40 ms"
            )
        }

        DiagnosticSection(
            title =
                "Hop Statistics"
        ) {

            DiagnosticRow(
                "Last packet",
                if (
                    diagnostics
                        .lastHopCount >
                    0
                ) {
                    "${diagnostics.lastHopCount} hop(s)"
                } else {
                    "—"
                }
            )

            DiagnosticRow(
                "Average",
                String.format(
                    Locale.US,
                    "%.2f hops",
                    diagnostics
                        .averageHopCount
                )
            )

            DiagnosticRow(
                "Maximum observed",
                "${diagnostics.maximumHopCount} hop(s)"
            )
        }

        Text(
            text =
                "Direct Peer Details",

            style =
                MaterialTheme
                    .typography
                    .titleLarge,

            fontWeight =
                FontWeight.Bold
        )

        if (
            diagnostics
                .directPeers
                .isEmpty()
        ) {

            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {

                Text(
                    text =
                        "No peer information available yet.",

                    modifier =
                        Modifier.padding(
                            16.dp
                        )
                )
            }

        } else {

            diagnostics
                .directPeers
                .forEach {
                    peer ->

                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                    ) {

                        Column(
                            modifier =
                                Modifier.padding(
                                    16.dp
                                ),

                            verticalArrangement =
                                Arrangement.spacedBy(
                                    5.dp
                                )
                        ) {

                            Text(
                                text =
                                    peer.callsign
                                        ?: "Unidentified peer",

                                style =
                                    MaterialTheme
                                        .typography
                                        .titleMedium,

                                fontWeight =
                                    FontWeight.Bold
                            )

                            DiagnosticRow(
                                "Connection",
                                if (
                                    peer.connected
                                ) {
                                    "Connected"
                                } else {
                                    "Disconnected"
                                }
                            )

                            DiagnosticRow(
                                "RSSI",
                                peer.rssi
                                    ?.let {
                                        "$it dBm"
                                    }
                                    ?: "Unknown"
                            )

                            DiagnosticRow(
                                "Link quality",
                                peer.linkQuality
                                    ?.let {
                                        "$it% · ${qualityLabel(it)}"
                                    }
                                    ?: "Measuring"
                            )

                            DiagnosticRow(
                                "Radio mode",
                                peer.phy
                            )

                            DiagnosticRow(
                                "Send queue",
                                "${peer.queueDepth} packets"
                            )

                            DiagnosticRow(
                                "MTU",
                                "${peer.mtu} bytes"
                            )

                            DiagnosticRow(
                                "Reconnect attempts",
                                peer.reconnectAttempts
                                    .toString()
                            )

                            DiagnosticRow(
                                "Address",
                                peer.address
                            )

                            peer.nodeId
                                ?.let {
                                    nodeId ->

                                    DiagnosticRow(
                                        "Node ID",
                                        (
                                            nodeId and
                                                0xFFFFFFFFL
                                            )
                                            .toString(
                                                16
                                            )
                                            .uppercase()
                                    )
                                }
                        }
                    }
                }
        }
    }
}

@Composable
private fun DiagnosticSection(
    title: String,
    content:
        @Composable () -> Unit
) {

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(
                    16.dp
                ),

            verticalArrangement =
                Arrangement.spacedBy(
                    8.dp
                )
        ) {

            Text(
                text =
                    title,

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold
            )

            HorizontalDivider()

            content()
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String
) {

    Row(
        modifier =
            Modifier
                .fillMaxWidth(),

        horizontalArrangement =
            Arrangement
                .SpaceBetween
    ) {

        Text(
            text =
                label,

            modifier =
                Modifier.weight(
                    1f
                )
        )

        Text(
            text =
                value,

            fontWeight =
                FontWeight.Medium
        )
    }
}

private fun qualityLabel(
    quality: Int
): String =
    when {
        quality >= 80 -> "Excellent"
        quality >= 60 -> "Good"
        quality >= 40 -> "Fair"
        else -> "Poor"
    }

private fun disconnectLabel(
    code: Int?
): String =
    when (code) {
        null -> "none"
        0 -> "0 (clean)"
        8 -> "8 (timeout)"
        19 -> "19 (remote closed)"
        22 -> "22 (local closed)"
        62 -> "62 (failed to establish)"
        133 -> "133 (generic error)"
        else -> code.toString()
    }
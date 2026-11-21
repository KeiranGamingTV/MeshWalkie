package com.keiranhaas.bluetoothwalkie.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keiranhaas.bluetoothwalkie.MeshUiState
import com.keiranhaas.bluetoothwalkie.mesh.MeshDiagnosticsState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.graphics.toArgb

@Composable
fun TopologyScreen(
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
                "Mesh Topology",

            style =
                MaterialTheme
                    .typography
                    .headlineSmall,

            fontWeight =
                FontWeight.Bold
        )

        Text(
            text =
                "Nodes are positioned farther from the center as their observed hop distance increases."
        )

        TopologyGraph(
            state =
                state,

            diagnostics =
                diagnostics
        )

        Text(
            text =
                "Direct Bluetooth Peers",

            style =
                MaterialTheme
                    .typography
                    .titleMedium,

            fontWeight =
                FontWeight.Bold
        )

        val connectedPeers =
            diagnostics.directPeers
                .filter {
                    it.connected
                }

        if (
            connectedPeers.isEmpty()
        ) {

            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {

                Text(
                    text =
                        "No direct peers connected.",

                    modifier =
                        Modifier.padding(
                            16.dp
                        )
                )
            }

        } else {

            for (
                peer in
                connectedPeers
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
                                4.dp
                            )
                    ) {

                        Text(
                            text =
                                peer.callsign
                                    ?: "Unidentified peer",

                            fontWeight =
                                FontWeight.Bold
                        )

                        Text(
                            "Signal: ${signalDescription(peer.rssi)}"
                        )

                        Text(
                            "RSSI: ${peer.rssi?.let { "$it dBm" } ?: "Waiting…"}"
                        )

                        Text(
                            "MTU: ${peer.mtu} bytes"
                        )

                        Text(
                            "Address: ${peer.address}"
                        )
                    }
                }
            }
        }

        Text(
            text =
                "Observed Mesh Nodes",

            style =
                MaterialTheme
                    .typography
                    .titleMedium,

            fontWeight =
                FontWeight.Bold
        )

        if (
            diagnostics
                .topologyNodes
                .isEmpty()
        ) {

            Text(
                "No encrypted node announcements received yet."
            )

        } else {

            for (
                node in
                diagnostics
                    .topologyNodes
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
                            )
                    ) {

                        Text(
                            text =
                                node.callsign,

                            fontWeight =
                                FontWeight.Bold
                        )

                        Text(
                            "Best observed path: ${node.bestHops} hop(s)"
                        )

                        Text(
                            "Most recent path: ${node.lastHops} hop(s)"
                        )

                        Text(
                            "Node ID: ${shortNodeId(node.nodeId)}"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyGraph(
    state: MeshUiState,
    diagnostics:
        MeshDiagnosticsState
) {

    val primary =
        MaterialTheme
            .colorScheme
            .primary

    val secondary =
        MaterialTheme
            .colorScheme
            .secondary

    val outline =
        MaterialTheme
            .colorScheme
            .outline

    val surface =
        MaterialTheme
            .colorScheme
            .surfaceVariant

    val textColor =
        MaterialTheme
            .colorScheme
            .onSurface

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
    ) {

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        340.dp
                    )
                    .padding(
                        12.dp
                    )
        ) {

            val center =
                Offset(
                    size.width / 2f,
                    size.height / 2f
                )

            val minimumDimension =
                min(
                    size.width,
                    size.height
                )

            drawCircle(
                color =
                    surface,

                radius =
                    minimumDimension *
                        0.47f,

                center =
                    center
            )

            drawCircle(
                color =
                    primary,

                radius =
                    28.dp.toPx(),

                center =
                    center
            )

            val centerPaint =
                Paint().apply {

                    color =
                        textColor
                            .toArgb()

                    textAlign =
                        Paint.Align.CENTER

                    textSize =
                        13.dp.toPx()

                    isAntiAlias =
                        true
                }

            drawContext
                .canvas
                .nativeCanvas
                .drawText(
                    state.callsign
                        .take(12),

                    center.x,

                    center.y +
                        46.dp.toPx(),

                    centerPaint
                )

            val nodes =
                diagnostics
                    .topologyNodes

            val nodePaint =
                Paint().apply {

                    color =
                        textColor
                            .toArgb()

                    textAlign =
                        Paint.Align.CENTER

                    textSize =
                        11.dp.toPx()

                    isAntiAlias =
                        true
                }

            nodes.forEachIndexed {
                index,
                node ->

                val angle =
                    (
                        2.0 *
                            PI *
                            index.toDouble() /
                            nodes.size
                                .coerceAtLeast(
                                    1
                                )
                        ) -
                        PI / 2.0

                val hopRing =
                    node.bestHops
                        .coerceIn(
                            1,
                            4
                        )

                val radius =
                    minimumDimension *
                        (
                            0.18f +
                                (
                                    hopRing -
                                        1
                                    ) *
                                0.085f
                            )

                val point =
                    Offset(
                        center.x +
                            cos(angle)
                                .toFloat() *
                            radius,

                        center.y +
                            sin(angle)
                                .toFloat() *
                            radius
                    )

                drawLine(
                    color =
                        outline,

                    start =
                        center,

                    end =
                        point,

                    strokeWidth =
                        1.5.dp
                            .toPx()
                )

                drawCircle(
                    color =
                        if (
                            node.bestHops ==
                            1
                        ) {
                            primary
                        } else {
                            secondary
                        },

                    radius =
                        18.dp.toPx(),

                    center =
                        point
                )

                drawContext
                    .canvas
                    .nativeCanvas
                    .drawText(
                        node.callsign
                            .take(10),

                        point.x,

                        point.y +
                            31.dp.toPx(),

                        nodePaint
                    )

                drawContext
                    .canvas
                    .nativeCanvas
                    .drawText(
                        "${node.bestHops}h",

                        point.x,

                        point.y +
                            43.dp.toPx(),

                        nodePaint
                    )
            }
        }
    }
}

private fun signalDescription(
    rssi: Int?
): String {

    if (
        rssi == null
    ) {
        return "Unknown"
    }

    return when {

        rssi >= -60 ->
            "Excellent"

        rssi >= -70 ->
            "Good"

        rssi >= -80 ->
            "Fair"

        rssi >= -90 ->
            "Weak"

        else ->
            "Very weak"
    }
}

private fun shortNodeId(
    nodeId: Long
): String {

    return (
        nodeId and
            0xFFFFFFFFL
        )
        .toString(
            16
        )
        .uppercase()
        .padStart(
            8,
            '0'
        )
}
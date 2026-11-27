package com.keiranhaas.bluetoothwalkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keiranhaas.bluetoothwalkie.MeshUiState
import com.keiranhaas.bluetoothwalkie.mesh.MeshDiagnostics
import com.keiranhaas.bluetoothwalkie.prefs.AppPreferences
import com.keiranhaas.bluetoothwalkie.prefs.RoomCodeStore
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun MeshWalkieApp(
    state: MeshUiState,
    initialRoomCode: String,
    onStartMesh: () -> Unit,
    onStopMesh: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onSaveCallsign:
        (String) -> Unit,
    onChannelChanged:
        (Int) -> Unit,
    onSaveRoomCode:
        (String) -> Unit
) {

    val diagnostics by
        MeshDiagnostics.state
            .collectAsState()

    var selectedScreen by
        rememberSaveable {
            mutableIntStateOf(
                0
            )
        }

    Scaffold(
        topBar = {

            TopAppBar(
                title = {
                    Text(
                        text = "> meshwalkie_",
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) {
        padding ->

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        padding
                    )
        ) {

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal =
                                8.dp
                        ),
                horizontalArrangement =
                    Arrangement
                        .SpaceEvenly
            ) {

                ScreenButton(
                    text =
                        "Radio",

                    selected =
                        selectedScreen ==
                            0,

                    onClick = {
                        selectedScreen =
                            0
                    }
                )

                ScreenButton(
                    text =
                        "Topology",

                    selected =
                        selectedScreen ==
                            1,

                    onClick = {
                        selectedScreen =
                            1
                    }
                )

                ScreenButton(
                    text =
                        "Diagnostics",

                    selected =
                        selectedScreen ==
                            2,

                    onClick = {
                        selectedScreen =
                            2
                    }
                )
            }

            HorizontalDivider()

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
            ) {

                when (
                    selectedScreen
                ) {

                    0 -> {

                        RadioScreen(
                            state =
                                state,

                            initialRoomCode =
                                initialRoomCode,

                            onStartMesh =
                                onStartMesh,

                            onStopMesh =
                                onStopMesh,

                            onPttDown =
                                onPttDown,

                            onPttUp =
                                onPttUp,

                            onSaveCallsign =
                                onSaveCallsign,

                            onChannelChanged =
                                onChannelChanged,

                            onSaveRoomCode =
                                onSaveRoomCode
                        )
                    }

                    1 -> {

                        TopologyScreen(
                            state =
                                state,

                            diagnostics =
                                diagnostics
                        )
                    }

                    else -> {

                        DiagnosticsScreen(
                            state =
                                state,

                            diagnostics =
                                diagnostics
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {

    if (
        selected
    ) {

        Button(
            onClick =
                onClick
        ) {
            Text(
                text.lowercase()
            )
        }

    } else {

        TextButton(
            onClick =
                onClick
        ) {
            Text(
                text.lowercase()
            )
        }
    }
}

@Composable
private fun RadioScreen(
    state: MeshUiState,
    initialRoomCode: String,
    onStartMesh: () -> Unit,
    onStopMesh: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onSaveCallsign:
        (String) -> Unit,
    onChannelChanged:
        (Int) -> Unit,
    onSaveRoomCode:
        (String) -> Unit
) {

    var callsign by
        rememberSaveable {
            mutableStateOf(
                state.callsign
            )
        }

    var roomCode by
        rememberSaveable {
            mutableStateOf(
                initialRoomCode
            )
        }

    LaunchedEffect(
        state.callsign
    ) {

        if (
            !state.transmitting
        ) {
            callsign =
                state.callsign
        }
    }

    val haptics =
        LocalHapticFeedback.current

    var lastNoticeSerial by
        remember {
            mutableLongStateOf(
                state.floorNoticeSerial
            )
        }

    LaunchedEffect(
        state.transmitting
    ) {

        if (
            state.transmitting
        ) {
            haptics.performHapticFeedback(
                HapticFeedbackType.Confirm
            )
        }
    }

    LaunchedEffect(
        state.floorNoticeSerial
    ) {

        if (
            state.floorNoticeSerial !=
            lastNoticeSerial
        ) {

            lastNoticeSerial =
                state.floorNoticeSerial

            haptics.performHapticFeedback(
                HapticFeedbackType.Reject
            )
        }
    }

    var pttHeld by
        remember {
            mutableStateOf(
                false
            )
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState(),
                    enabled =
                        !pttHeld
                )
                .padding(
                    20.dp
                ),
        horizontalAlignment =
            Alignment.CenterHorizontally,
        verticalArrangement =
            Arrangement.spacedBy(
                16.dp
            )
    ) {

        StatusCard(
            state
        )

        SettingsCard(
            state =
                state,

            callsign =
                callsign,

            roomCode =
                roomCode,

            onCallsignChanged = {
                callsign =
                    it.take(
                        AppPreferences
                            .MAX_CALLSIGN_LENGTH
                    )
            },

            onRoomCodeChanged = {
                roomCode =
                    it
            },

            onSaveCallsign = {
                onSaveCallsign(
                    callsign
                )
            },

            onSaveRoomCode = {
                onSaveRoomCode(
                    roomCode
                )
            },

            onChannelChanged =
                onChannelChanged
        )

        if (
            !state.bluetoothSupported
        ) {

            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {

                Text(
                    text =
                        "This phone does not appear to support the BLE advertising/scanning roles required by MeshWalkie.",

                    modifier =
                        Modifier.padding(
                            16.dp
                        ),

                    color =
                        MaterialTheme
                            .colorScheme
                            .error
                )
            }
        }

        if (
            state.networkRunning
        ) {

            OutlinedButton(
                onClick =
                    onStopMesh,

                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {

                Text(
                    "Stop Mesh"
                )
            }

        } else {

            Button(
                onClick =
                    onStartMesh,

                enabled =
                    state
                        .bluetoothSupported &&
                    state
                        .roomCodeConfigured,

                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {

                Text(
                    "Start Encrypted Mesh"
                )
            }
        }

        Spacer(
            Modifier.height(
                8.dp
            )
        )

        PushToTalkButton(
            enabled =
                state.networkRunning &&
                    state.bluetoothSupported,

            transmitting =
                state.transmitting,

            busyCallsign =
                state.receivingFrom,

            onPttDown =
                onPttDown,

            onPttUp =
                onPttUp,

            onHoldChange = {
                pttHeld = it
            }
        )

        state.receivingFrom
            ?.let {
                callsignName ->

                Text(
                    text =
                        "Receiving: $callsignName",

                    fontSize =
                        20.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        MaterialTheme
                            .colorScheme
                            .primary
                )
            }

        HorizontalDivider()

        Text(
            text =
                "Voice and control data are protected with AES-256-GCM. Phones must use the same channel and exact room code to hear one another.",

            style =
                MaterialTheme
                    .typography
                    .bodyMedium,

            textAlign =
                TextAlign.Center
        )
    }
}

@Composable
private fun StatusCard(
    state: MeshUiState
) {

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(
                    18.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    8.dp
                )
        ) {

            Text(
                text =
                    if (
                        state.networkRunning
                    ) {
                        "Encrypted Mesh Online"
                    } else {
                        "Mesh Offline"
                    },

                style =
                    MaterialTheme
                        .typography
                        .titleLarge,

                fontWeight =
                    FontWeight.Bold
            )

            Text(
                "Direct peers: ${state.peerCount}"
            )

            Text(
                "Channel: ${state.channel}"
            )

            Text(
                "Callsign: ${state.callsign}"
            )

            Text(
                text =
                    if (
                        state
                            .roomCodeConfigured
                    ) {
                        "Room encryption: configured"
                    } else {
                        "Room encryption: not configured"
                    }
            )

            Text(
                text =
                    state.status,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall
            )
        }
    }
}

@Composable
private fun SettingsCard(
    state: MeshUiState,
    callsign: String,
    roomCode: String,
    onCallsignChanged:
        (String) -> Unit,
    onRoomCodeChanged:
        (String) -> Unit,
    onSaveCallsign: () -> Unit,
    onSaveRoomCode: () -> Unit,
    onChannelChanged:
        (Int) -> Unit
) {

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
    ) {

        Column(
            modifier =
                Modifier.padding(
                    18.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(
                    12.dp
                )
        ) {

            Text(
                text =
                    "Radio & Encryption",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold
            )

            OutlinedTextField(
                value =
                    callsign,

                onValueChange =
                    onCallsignChanged,

                modifier =
                    Modifier
                        .fillMaxWidth(),

                singleLine =
                    true,

                label = {
                    Text(
                        "Callsign"
                    )
                }
            )

            Button(
                onClick =
                    onSaveCallsign,

                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {

                Text(
                    "Save Callsign"
                )
            }

            OutlinedTextField(
                value =
                    roomCode,

                onValueChange =
                    onRoomCodeChanged,

                modifier =
                    Modifier
                        .fillMaxWidth(),

                singleLine =
                    true,

                visualTransformation =
                    PasswordVisualTransformation(),

                label = {
                    Text(
                        "Shared Room Code"
                    )
                },

                supportingText = {

                    Text(
                        "Minimum 8 characters. A longer phrase is recommended."
                    )
                }
            )

            Button(
                onClick =
                    onSaveRoomCode,

                enabled =
                    roomCode
                        .trim()
                        .length >=
                    RoomCodeStore
                        .MIN_ROOM_CODE_LENGTH,

                modifier =
                    Modifier
                        .fillMaxWidth()
            ) {

                Text(
                    "Save Encrypted Room"
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth(),

                verticalAlignment =
                    Alignment
                        .CenterVertically,

                horizontalArrangement =
                    Arrangement
                        .SpaceBetween
            ) {

                OutlinedButton(
                    onClick = {

                        onChannelChanged(
                            state.channel -
                                1
                        )
                    },

                    enabled =
                        state.channel >
                            AppPreferences
                                .MIN_CHANNEL
                ) {

                    Text(
                        "−"
                    )
                }

                Column(
                    horizontalAlignment =
                        Alignment
                            .CenterHorizontally
                ) {

                    Text(
                        "Channel"
                    )

                    Text(
                        text =
                            state.channel
                                .toString(),

                        fontSize =
                            28.sp,

                        fontWeight =
                            FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = {

                        onChannelChanged(
                            state.channel +
                                1
                        )
                    },

                    enabled =
                        state.channel <
                            AppPreferences
                                .MAX_CHANNEL
                ) {

                    Text(
                        "+"
                    )
                }
            }
        }
    }
}

@Composable
private fun PushToTalkButton(
    enabled: Boolean,
    transmitting: Boolean,
    busyCallsign: String?,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onHoldChange: (Boolean) -> Unit
) {

    val busy =
        !transmitting &&
                busyCallsign != null

    val scheme =
        MaterialTheme.colorScheme

    val background =
        when {
            !enabled -> scheme.surfaceVariant
            transmitting -> scheme.errorContainer
            busy -> scheme.tertiaryContainer
            else -> scheme.primaryContainer
        }

    val accent =
        when {
            !enabled -> scheme.outline
            transmitting -> scheme.error
            busy -> scheme.tertiary
            else -> scheme.primary
        }

    Surface(
        shape =
            CutCornerShape(24.dp),

        color =
            background,

        contentColor =
            accent,

        border =
            BorderStroke(
                3.dp,
                accent
            ),

        modifier =
            Modifier
                .size(
                    220.dp
                )
                .pointerInput(
                    enabled
                ) {

                    awaitEachGesture {

                        val down =
                            awaitFirstDown(
                                requireUnconsumed =
                                    false
                            )

                        if (
                            !enabled
                        ) {
                            return@awaitEachGesture
                        }

                        down.consume()

                        onHoldChange(true)

                        onPttDown()

                        try {

                            do {

                                val event =
                                    awaitPointerEvent()

                                event.changes
                                    .forEach {
                                        it.consume()
                                    }

                            } while (
                                event.changes
                                    .any {
                                        it.pressed
                                    }
                            )

                        } finally {

                            onHoldChange(false)

                            onPttUp()
                        }
                    }
                }
    ) {

        Box(
            modifier =
                Modifier
                    .fillMaxSize(),

            contentAlignment =
                Alignment.Center
        ) {

            Column(
                horizontalAlignment =
                    Alignment
                        .CenterHorizontally
            ) {

                Text(
                    text =
                        when {
                            transmitting ->
                                "TRANSMITTING"

                            busy ->
                                "CHANNEL BUSY"

                            else ->
                                "PUSH"
                        },

                    fontWeight =
                        FontWeight.Bold,

                    fontSize =
                        22.sp
                )

                Text(
                    text =
                        when {
                            transmitting ->
                                "release to stop"

                            busy ->
                                "$busyCallsign is talking"

                            else ->
                                "to talk"
                        },

                    fontSize =
                        16.sp,

                    textAlign =
                        TextAlign.Center
                )
            }
        }
    }
}
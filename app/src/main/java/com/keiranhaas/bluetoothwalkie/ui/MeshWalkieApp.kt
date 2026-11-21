package com.keiranhaas.bluetoothwalkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keiranhaas.bluetoothwalkie.MeshUiState
import com.keiranhaas.bluetoothwalkie.prefs.AppPreferences

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun MeshWalkieApp(
    state: MeshUiState,
    onStartMesh: () -> Unit,
    onStopMesh: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onSaveCallsign: (
        String
    ) -> Unit,
    onChannelChanged: (
        Int
    ) -> Unit
) {

    var callsign by
        rememberSaveable {
            mutableStateOf(
                state.callsign
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "MeshWalkie"
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
                    .padding(padding)
                    .verticalScroll(
                        rememberScrollState()
                    )
                    .padding(20.dp),
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
                state = state,
                callsign = callsign,
                onCallsignChanged = {
                    callsign = it
                        .take(
                            AppPreferences
                                .MAX_CALLSIGN_LENGTH
                        )
                },
                onSaveCallsign = {
                    onSaveCallsign(
                        callsign
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
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        text =
                            "This phone does not appear to support the Bluetooth LE advertising/scanning roles required by MeshWalkie.",
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
                        Modifier.fillMaxWidth()
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
                        state.bluetoothSupported,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Start Mesh"
                    )
                }
            }

            Spacer(
                Modifier.height(
                    10.dp
                )
            )

            PushToTalkButton(
                enabled =
                    state.networkRunning &&
                        state.bluetoothSupported,
                transmitting =
                    state.transmitting,
                onPttDown =
                    onPttDown,
                onPttUp =
                    onPttUp
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

            Spacer(
                Modifier.height(
                    8.dp
                )
            )

            HorizontalDivider()

            Text(
                text =
                    "MeshWalkie uses nearby phones as Bluetooth relays. No internet connection is required.",
                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,
                textAlign =
                    TextAlign.Center
            )

            Text(
                text =
                    "Hold the PTT button while speaking. Release it when finished.",
                style =
                    MaterialTheme
                        .typography
                        .bodySmall,
                textAlign =
                    TextAlign.Center
            )
        }
    }
}

@Composable
private fun StatusCard(
    state: MeshUiState
) {

    Card(
        modifier =
            Modifier.fillMaxWidth()
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
                        "Mesh Online"
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
                "Connected peers: ${state.peerCount}"
            )

            Text(
                "Channel: ${state.channel}"
            )

            Text(
                "Callsign: ${state.callsign}"
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
    onCallsignChanged: (
        String
    ) -> Unit,
    onSaveCallsign: () -> Unit,
    onChannelChanged: (
        Int
    ) -> Unit
) {

    Card(
        modifier =
            Modifier.fillMaxWidth()
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
                    "Radio Settings",
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
                    Modifier.fillMaxWidth(),
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
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "Save Callsign"
                )
            }

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {

                OutlinedButton(
                    onClick = {
                        onChannelChanged(
                            state.channel - 1
                        )
                    },
                    enabled =
                        state.channel >
                            AppPreferences
                                .MIN_CHANNEL
                ) {
                    Text("−")
                }

                Column(
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Text(
                        "Channel"
                    )

                    Text(
                        text =
                            state.channel.toString(),
                        fontSize =
                            28.sp,
                        fontWeight =
                            FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = {
                        onChannelChanged(
                            state.channel + 1
                        )
                    },
                    enabled =
                        state.channel <
                            AppPreferences
                                .MAX_CHANNEL
                ) {
                    Text("+")
                }
            }
        }
    }
}

@Composable
private fun PushToTalkButton(
    enabled: Boolean,
    transmitting: Boolean,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit
) {

    val background =
        when {

            !enabled ->
                MaterialTheme
                    .colorScheme
                    .surfaceVariant

            transmitting ->
                MaterialTheme
                    .colorScheme
                    .errorContainer

            else ->
                MaterialTheme
                    .colorScheme
                    .primaryContainer
        }

    Surface(
        shape =
            CircleShape,
        tonalElevation =
            8.dp,
        modifier =
            Modifier
                .size(
                    220.dp
                )
                .pointerInput(
                    enabled
                ) {

                    detectTapGestures(
                        onPress = {

                            if (!enabled) {
                                return@detectTapGestures
                            }

                            onPttDown()

                            try {
                                awaitRelease()
                            } finally {
                                onPttUp()
                            }
                        }
                    )
                }
    ) {

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        background,
                        CircleShape
                    ),
            contentAlignment =
                Alignment.Center
        ) {

            Column(
                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {

                Text(
                    text =
                        if (
                            transmitting
                        ) {
                            "TRANSMITTING"
                        } else {
                            "PUSH"
                        },
                    fontWeight =
                        FontWeight.Bold,
                    fontSize =
                        22.sp
                )

                Text(
                    text =
                        if (
                            transmitting
                        ) {
                            "Release to stop"
                        } else {
                            "TO TALK"
                        },
                    fontSize =
                        18.sp
                )
            }
        }
    }
}
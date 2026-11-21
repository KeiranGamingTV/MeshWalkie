package com.keiranhaas.bluetoothwalkie

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.keiranhaas.bluetoothwalkie.prefs.AppPreferences
import com.keiranhaas.bluetoothwalkie.prefs.RoomCodeStore
import com.keiranhaas.bluetoothwalkie.ui.MeshWalkieApp
import com.keiranhaas.bluetoothwalkie.ui.theme.MeshWalkieTheme

class MainActivity :
    ComponentActivity() {

    private lateinit var preferences:
        AppPreferences

    private lateinit var roomCodeStore:
        RoomCodeStore

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions()
        ) {

            if (
                hasRequiredCorePermissions()
            ) {
                startMeshService()
            } else {

                MeshRuntime
                    .setStatus(
                        "Bluetooth and microphone permissions are required"
                    )
            }
        }

    override fun onCreate(
        savedInstanceState:
            Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        preferences =
            AppPreferences(
                this
            )

        roomCodeStore =
            RoomCodeStore(
                this
            )

        MeshRuntime.setConfig(
            callsign =
                preferences.callsign,

            channel =
                preferences.channel,

            roomCodeConfigured =
                roomCodeStore
                    .hasRoomCode()
        )

        val initialRoomCode =
            roomCodeStore.load()

        setContent {

            val state by
                MeshRuntime.state
                    .collectAsState()

            MeshWalkieTheme {

                MeshWalkieApp(
                    state =
                        state,

                    initialRoomCode =
                        initialRoomCode,

                    onStartMesh = {

                        if (
                            roomCodeStore
                                .load()
                                .length <
                            RoomCodeStore
                                .MIN_ROOM_CODE_LENGTH
                        ) {

                            MeshRuntime
                                .setStatus(
                                    "Set a room code of at least 8 characters first"
                                )

                        } else {

                            requestPermissionsAndStart()
                        }
                    },

                    onStopMesh = {
                        sendServiceAction(
                            MeshService
                                .ACTION_STOP
                        )
                    },

                    onPttDown = {

                        if (
                            hasRequiredCorePermissions()
                        ) {

                            sendServiceAction(
                                MeshService
                                    .ACTION_PTT_DOWN
                            )

                        } else {

                            requestPermissionsAndStart()
                        }
                    },

                    onPttUp = {

                        if (
                            state.networkRunning
                        ) {

                            sendServiceAction(
                                MeshService
                                    .ACTION_PTT_UP
                            )
                        }
                    },

                    onSaveCallsign = {
                        callsign ->

                        preferences.callsign =
                            callsign

                        MeshRuntime
                            .setConfig(
                                callsign =
                                    preferences.callsign,

                                channel =
                                    preferences.channel,

                                roomCodeConfigured =
                                    roomCodeStore
                                        .hasRoomCode()
                            )

                        if (
                            state.networkRunning
                        ) {

                            sendServiceAction(
                                MeshService
                                    .ACTION_CONFIG_CHANGED
                            )
                        }
                    },

                    onChannelChanged = {
                        newChannel ->

                        preferences.channel =
                            newChannel

                        MeshRuntime
                            .setConfig(
                                callsign =
                                    preferences.callsign,

                                channel =
                                    preferences.channel,

                                roomCodeConfigured =
                                    roomCodeStore
                                        .hasRoomCode()
                            )

                        if (
                            state.networkRunning
                        ) {

                            sendServiceAction(
                                MeshService
                                    .ACTION_CONFIG_CHANGED
                            )
                        }
                    },

                    onSaveRoomCode = {
                        roomCode ->

                        val cleaned =
                            roomCode.trim()

                        if (
                            cleaned.length >=
                            RoomCodeStore
                                .MIN_ROOM_CODE_LENGTH
                        ) {

                            roomCodeStore
                                .save(
                                    cleaned
                                )

                            MeshRuntime
                                .setRoomCodeConfigured(
                                    true
                                )

                            MeshRuntime
                                .setStatus(
                                    "Encrypted room code saved"
                                )

                            if (
                                state.networkRunning
                            ) {

                                sendServiceAction(
                                    MeshService
                                        .ACTION_CONFIG_CHANGED
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    private fun requestPermissionsAndStart() {

        val missing =
            requiredPermissions()
                .filter {
                    permission ->

                    ContextCompat
                        .checkSelfPermission(
                            this,
                            permission
                        ) !=
                        PackageManager
                            .PERMISSION_GRANTED
                }

        if (
            missing.isEmpty()
        ) {

            startMeshService()

        } else {

            permissionLauncher
                .launch(
                    missing
                        .toTypedArray()
                )
        }
    }

    private fun startMeshService() {

        val intent =
            Intent(
                this,
                MeshService::class.java
            ).apply {

                action =
                    MeshService
                        .ACTION_START
            }

        ContextCompat
            .startForegroundService(
                this,
                intent
            )
    }

    private fun sendServiceAction(
        actionName: String
    ) {

        val intent =
            Intent(
                this,
                MeshService::class.java
            ).apply {
                action =
                    actionName
            }

        try {
            startService(
                intent
            )
        } catch (_: Exception) {

            if (
                actionName !=
                MeshService
                    .ACTION_STOP
            ) {

                ContextCompat
                    .startForegroundService(
                        this,
                        intent
                    )
            }
        }
    }

    private fun hasRequiredCorePermissions():
        Boolean {

        return corePermissions()
            .all {
                permission ->

                ContextCompat
                    .checkSelfPermission(
                        this,
                        permission
                    ) ==
                    PackageManager
                        .PERMISSION_GRANTED
            }
    }

    private fun corePermissions():
        List<String> {

        val permissions =
            mutableListOf(
                Manifest.permission
                    .RECORD_AUDIO
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            permissions +=
                Manifest.permission
                    .BLUETOOTH_SCAN

            permissions +=
                Manifest.permission
                    .BLUETOOTH_CONNECT

            permissions +=
                Manifest.permission
                    .BLUETOOTH_ADVERTISE

        } else {

            permissions +=
                Manifest.permission
                    .ACCESS_FINE_LOCATION
        }

        return permissions
    }

    private fun requiredPermissions():
        List<String> {

        val permissions =
            corePermissions()
                .toMutableList()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            permissions +=
                Manifest.permission
                    .POST_NOTIFICATIONS
        }

        return permissions
    }
}
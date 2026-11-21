package com.keiranhaas.bluetoothwalkie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.keiranhaas.bluetoothwalkie.audio.WalkieAudioEngine
import com.keiranhaas.bluetoothwalkie.mesh.MeshEngine
import com.keiranhaas.bluetoothwalkie.prefs.AppPreferences
import com.keiranhaas.bluetoothwalkie.prefs.RoomCodeStore
import android.os.Handler
import android.os.Looper
import com.keiranhaas.bluetoothwalkie.mesh.TalkRequestResult
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class MeshService :
    Service() {

    private lateinit var preferences:
        AppPreferences

    private lateinit var roomCodeStore:
        RoomCodeStore

    private lateinit var audioEngine:
        WalkieAudioEngine

    private lateinit var meshEngine:
        MeshEngine

    private var networkStarted =
        false

    private var resumeAfterBluetooth =
        false

    private val bluetoothReceiver =
        object :
            BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (
                    intent?.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )
                ) {

                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF -> {

                        if (
                            networkStarted
                        ) {

                            resumeAfterBluetooth =
                                true

                            stopTransmission()
                            stopMesh()

                            MeshRuntime.setStatus(
                                "Bluetooth is off — mesh paused"
                            )
                        }
                    }

                    BluetoothAdapter.STATE_ON -> {

                        if (
                            resumeAfterBluetooth
                        ) {

                            resumeAfterBluetooth =
                                false

                            mainHandler.postDelayed(
                                {
                                    startMesh()
                                },
                                1_000L
                            )
                        }
                    }
                }
            }
        }

    @Volatile
    private var transmitting =
        false

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val transmitTimeout =
        Runnable {

            if (transmitting) {

                stopTransmission()

                MeshRuntime.postFloorNotice(
                    "Transmission limit reached (${MAX_TRANSMIT_MS / 1000}s)"
                )
            }
        }

    private var receivingSessionId:
        Long? =
        null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

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

        audioEngine =
            WalkieAudioEngine(
                this
            )

        meshEngine =
            MeshEngine(
                context =
                    this,

                nodeId =
                    preferences.nodeId,

                onVoiceStart = {
                    sessionId,
                    callsign,
                    firstSequence ->

                    receivingSessionId =
                        sessionId

                    MeshRuntime
                        .setReceivingFrom(
                            callsign
                        )

                    audioEngine
                        .beginRemoteSession(
                            sessionId,
                            firstSequence
                        )
                },

                onVoiceFrame = {
                    sessionId,
                    sequence,
                    frame ->

                    audioEngine
                        .queueRemoteFrame(
                            sessionId =
                                sessionId,

                            sequence =
                                sequence,

                            encoded =
                                frame
                        )
                },

                onVoiceEnd = {
                    sessionId,
                    endSequence ->

                    audioEngine
                        .endRemoteSession(
                            sessionId =
                                sessionId,

                            endSequence =
                                endSequence
                        )

                    if (
                        receivingSessionId ==
                        sessionId
                    ) {

                        receivingSessionId =
                            null

                        MeshRuntime
                            .setReceivingFrom(
                                null
                            )
                    }
                },

                onTransmitPreempted = {
                    winner ->

                    mainHandler.post {

                        if (
                            transmitting
                        ) {

                            stopTransmission()

                            MeshRuntime
                                .postFloorNotice(
                                    "Channel taken by $winner"
                                )
                        }
                    }
                },

                onPeerCountChanged = {
                    count ->

                    MeshRuntime
                        .setPeerCount(
                            count
                        )
                },

                onStatus = {
                    status ->

                    MeshRuntime
                        .setStatus(
                            status
                        )
                }
            )

        updateMeshConfiguration()

        MeshRuntime
            .setBluetoothSupported(
                meshEngine
                    .isBluetoothAvailable()
            )

        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            IntentFilter(
                BluetoothAdapter
                    .ACTION_STATE_CHANGED
            ),
            ContextCompat
                .RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (
            intent?.action
                ?: ACTION_START
        ) {

            ACTION_START -> {
                startMesh()
            }

            ACTION_PTT_DOWN -> {

                if (
                    !networkStarted
                ) {
                    startMesh()
                }

                startTransmission()
            }

            ACTION_PTT_UP -> {
                stopTransmission()
            }

            ACTION_CONFIG_CHANGED -> {

                if (
                    transmitting
                ) {
                    stopTransmission()
                }

                updateMeshConfiguration()

                if (
                    networkStarted
                ) {

                    meshEngine
                        .sendAnnouncement()

                    MeshRuntime
                        .setStatus(
                            "Encrypted room updated"
                        )
                }
            }

            ACTION_STOP -> {

                resumeAfterBluetooth =
                    false

                stopTransmission()
                stopMesh()

                stopForeground(
                    STOP_FOREGROUND_REMOVE
                )

                stopSelf()

                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? =
        null

    private fun updateMeshConfiguration() {

        val roomCode =
            roomCodeStore.load()

        meshEngine.updateConfig(
            callsign =
                preferences.callsign,

            channel =
                preferences.channel,

            roomCode =
                roomCode
        )

        MeshRuntime.setConfig(
            callsign =
                preferences.callsign,

            channel =
                preferences.channel,

            roomCodeConfigured =
                roomCode.isNotBlank()
        )
    }

    private fun startMesh() {

        if (
            networkStarted
        ) {
            return
        }

        updateMeshConfiguration()

        if (
            !meshEngine
                .hasEncryptionKey()
        ) {

            MeshRuntime
                .setRunning(
                    running =
                        false,

                    status =
                        "Set a room code before starting the mesh"
                )

            return
        }

        updateForeground(
            microphoneActive =
                false
        )

        val started =
            try {
                meshEngine.start()
            } catch (
                _: SecurityException
            ) {
                false
            }

        if (
            !started
        ) {

            networkStarted =
                false

            MeshRuntime
                .setRunning(
                    running =
                        false,

                    status =
                        "Unable to start encrypted Bluetooth mesh"
                )

            return
        }

        networkStarted =
            true

        MeshRuntime
            .setRunning(
                running =
                    true,

                status =
                    "Encrypted mesh online — searching for peers"
            )
    }

    private fun stopMesh() {

        if (
            !networkStarted
        ) {
            return
        }

        try {
            meshEngine.stop()
        } catch (_: Exception) {
        }

        networkStarted =
            false

        MeshRuntime
            .setPeerCount(
                0
            )

        MeshRuntime
            .setReceivingFrom(
                null
            )

        MeshRuntime
            .setRunning(
                running =
                    false,

                status =
                    "Mesh stopped"
            )
    }

    private fun startTransmission() {

        if (
            !networkStarted ||
            transmitting
        ) {
            return
        }

        when (
            val result =
                meshEngine.beginTalking()
        ) {

            is TalkRequestResult.Granted -> Unit

            is TalkRequestResult.Busy -> {

                MeshRuntime.postFloorNotice(
                    "Channel busy — ${result.callsign} is talking"
                )

                return
            }

            is TalkRequestResult.NotRunning -> return
        }

        audioEngine
            .clearRemoteReception()

        receivingSessionId =
            null

        MeshRuntime
            .setReceivingFrom(
                null
            )

        updateForeground(
            microphoneActive =
                true
        )

        val started =
            audioEngine
                .startCapture {
                    compressedFrame ->

                    meshEngine
                        .sendVoiceFrame(
                            compressedFrame
                        )
                }

        if (
            !started
        ) {

            meshEngine
                .endTalking()

            updateForeground(
                microphoneActive =
                    false
            )

            MeshRuntime
                .setStatus(
                    "Unable to open microphone"
                )

            return
        }

        transmitting =
            true

        MeshRuntime
            .setTransmitting(
                true
            )

        MeshRuntime
            .setStatus(
                "Encrypted transmission on channel ${preferences.channel}"
            )

        mainHandler.postDelayed(
            transmitTimeout,
            MAX_TRANSMIT_MS
        )
    }

    private fun stopTransmission() {

        if (
            !transmitting
        ) {
            return
        }

        mainHandler.removeCallbacks(
            transmitTimeout
        )

        audioEngine
            .stopCapture()

        meshEngine
            .endTalking()

        transmitting =
            false

        MeshRuntime
            .setTransmitting(
                false
            )

        updateForeground(
            microphoneActive =
                false
        )

        MeshRuntime
            .setStatus(
                if (
                    networkStarted
                ) {
                    "Listening on encrypted channel ${preferences.channel}"
                } else {
                    "Mesh stopped"
                }
            )
    }

    private fun updateForeground(
        microphoneActive: Boolean
    ) {

        val notification =
            buildNotification(
                microphoneActive
            )

        var foregroundTypes =
            ServiceInfo
                .FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        if (
            microphoneActive
        ) {

            foregroundTypes =
                foregroundTypes or
                    ServiceInfo
                        .FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        startForeground(
            NOTIFICATION_ID,
            notification,
            foregroundTypes
        )
    }

    private fun buildNotification(
        microphoneActive: Boolean
    ): Notification {

        val activityIntent =
            Intent(
                this,
                MainActivity::class.java
            )

        val pendingIntent =
            PendingIntent
                .getActivity(
                    this,
                    0,
                    activityIntent,

                    PendingIntent
                        .FLAG_UPDATE_CURRENT or
                        PendingIntent
                            .FLAG_IMMUTABLE
                )

        val text =
            if (
                microphoneActive
            ) {

                "Encrypted transmission • Channel ${preferences.channel}"

            } else {

                "Encrypted mesh active • ${MeshRuntime.state.value.peerCount} peer(s)"
            }

        return Notification
            .Builder(
                this,
                NOTIFICATION_CHANNEL_ID
            )
            .setSmallIcon(
                R.drawable.ic_mesh
            )
            .setContentTitle(
                "MeshWalkie"
            )
            .setContentText(
                text
            )
            .setContentIntent(
                pendingIntent
            )
            .setOngoing(
                true
            )
            .setOnlyAlertOnce(
                true
            )
            .build()
    }

    private fun createNotificationChannel() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Mesh network",
                NotificationManager
                    .IMPORTANCE_LOW
            ).apply {

                description =
                    "Shows when the encrypted MeshWalkie network is active."
            }

        manager
            .createNotificationChannel(
                channel
            )
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(
                bluetoothReceiver
            )
        } catch (_: Exception) {
        }

        mainHandler.removeCallbacks(
            transmitTimeout
        )

        try {
            audioEngine.shutdown()
        } catch (_: Exception) {
        }

        try {
            meshEngine.stop()
        } catch (_: Exception) {
        }

        MeshRuntime
            .setPeerCount(
                0
            )

        MeshRuntime
            .setTransmitting(
                false
            )

        MeshRuntime
            .setReceivingFrom(
                null
            )

        MeshRuntime
            .setRunning(
                running =
                    false,

                status =
                    "Mesh stopped"
            )

        super.onDestroy()
    }

    companion object {

        const val ACTION_START =
            "com.keiranhaas.bluetoothwalkie.START"

        const val ACTION_STOP =
            "com.keiranhaas.bluetoothwalkie.STOP"

        const val ACTION_PTT_DOWN =
            "com.keiranhaas.bluetoothwalkie.PTT_DOWN"

        const val ACTION_PTT_UP =
            "com.keiranhaas.bluetoothwalkie.PTT_UP"

        const val ACTION_CONFIG_CHANGED =
            "com.keiranhaas.bluetoothwalkie.CONFIG_CHANGED"

        private const val NOTIFICATION_CHANNEL_ID =
            "mesh_network"

        private const val NOTIFICATION_ID =
            1001

        private const val MAX_TRANSMIT_MS =
            60_000L
    }
}
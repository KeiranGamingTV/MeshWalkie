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

class MeshService :
    Service() {

    private lateinit var preferences:
        AppPreferences

    private lateinit var audioEngine:
        WalkieAudioEngine

    private lateinit var meshEngine:
        MeshEngine

    private var networkStarted =
        false

    private var transmitting =
        false

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        preferences =
            AppPreferences(this)

        MeshRuntime.setConfig(
            callsign =
                preferences.callsign,
            channel =
                preferences.channel
        )

        audioEngine =
            WalkieAudioEngine(
                this
            )

        meshEngine =
            MeshEngine(
                context = this,
                nodeId =
                    preferences.nodeId,
                channelProvider = {
                    preferences.channel
                },
                callsignProvider = {
                    preferences.callsign
                },
                onVoiceFrame = {
                    audioEngine
                        .playCompressedFrame(
                            it
                        )
                },
                onIncomingStarted = {
                    MeshRuntime
                        .setReceivingFrom(
                            it
                        )
                },
                onIncomingEnded = {
                    MeshRuntime
                        .setReceivingFrom(
                            null
                        )
                },
                onPeerCountChanged = {
                    MeshRuntime
                        .setPeerCount(
                            it
                        )
                },
                onStatus = {
                    MeshRuntime
                        .setStatus(
                            it
                        )
                }
            )

        MeshRuntime
            .setBluetoothSupported(
                meshEngine
                    .isBluetoothAvailable()
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

                MeshRuntime.setConfig(
                    callsign =
                        preferences.callsign,
                    channel =
                        preferences.channel
                )
            }

            ACTION_STOP -> {

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
    ): IBinder? = null

    private fun startMesh() {

        if (
            networkStarted
        ) {
            return
        }

        /*
         * Start with connectedDevice
         * only. The microphone type is
         * enabled only while PTT is held.
         */
        updateForeground(
            microphoneActive =
                false
        )

        val started =
            try {
                meshEngine.start()
            } catch (
                exception: SecurityException
            ) {
                false
            }

        if (!started) {

            networkStarted =
                false

            MeshRuntime.setRunning(
                running = false,
                status =
                    "Unable to start Bluetooth mesh"
            )

            return
        }

        networkStarted =
            true

        MeshRuntime.setRunning(
            running = true,
            status =
                "Mesh online — searching for peers"
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

        MeshRuntime.setPeerCount(
            0
        )

        MeshRuntime.setRunning(
            running = false,
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

        /*
         * Android requires the
         * microphone FGS type before
         * the service accesses the mic.
         */
        updateForeground(
            microphoneActive =
                true
        )

        meshEngine
            .beginTalking()

        val started =
            audioEngine
                .startCapture {
                    compressedFrame ->

                    meshEngine
                        .sendVoiceFrame(
                            compressedFrame
                        )
                }

        if (!started) {

            meshEngine
                .endTalking()

            updateForeground(
                microphoneActive =
                    false
            )

            MeshRuntime.setStatus(
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

        MeshRuntime.setStatus(
            "Transmitting on channel ${preferences.channel}"
        )
    }

    private fun stopTransmission() {

        if (
            !transmitting
        ) {
            return
        }

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

        MeshRuntime.setStatus(
            if (
                networkStarted
            ) {
                "Listening on channel ${preferences.channel}"
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
            PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val text =
            if (
                microphoneActive
            ) {
                "Transmitting on channel ${preferences.channel}"
            } else {
                "Mesh active • ${MeshRuntime.state.value.peerCount} peer(s)"
            }

        return Notification.Builder(
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
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Shows when the MeshWalkie Bluetooth network is active."
            }

        manager.createNotificationChannel(
            channel
        )
    }

    override fun onDestroy() {

        try {
            audioEngine.shutdown()
        } catch (_: Exception) {
        }

        try {
            meshEngine.stop()
        } catch (_: Exception) {
        }

        MeshRuntime.setPeerCount(
            0
        )

        MeshRuntime.setTransmitting(
            false
        )

        MeshRuntime.setReceivingFrom(
            null
        )

        MeshRuntime.setRunning(
            running = false,
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
    }
}
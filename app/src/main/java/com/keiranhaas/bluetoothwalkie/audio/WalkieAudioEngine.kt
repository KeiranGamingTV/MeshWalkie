package com.keiranhaas.bluetoothwalkie.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import com.keiranhaas.bluetoothwalkie.mesh.MeshDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TreeMap
import kotlin.math.max
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

class WalkieAudioEngine(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO
        )

    @Volatile
    private var recorder:
        AudioRecord? =
        null

    private var captureJob:
        Job? =
        null

    private var playoutJob:
        Job? =
        null

    private var audioTrack:
        AudioTrack? =
        null

    private var encoderStepIndex =
        0

    private var noiseSuppressor:
            NoiseSuppressor? =
        null

    private var automaticGainControl:
            AutomaticGainControl? =
        null

    private val voiceFilter =
        VoiceFilter()

    private var adaptiveTargetMs =
        JITTER_TARGET_MS

    private var lastJitterIssueMs =
        SystemClock.elapsedRealtime()

    private var lastTargetChangeMs =
        SystemClock.elapsedRealtime()

    private val receiveLock =
        Any()

    private var receiveSession:
        ReceiveSession? =
        null

    init {
        startPlayoutWorker()
    }

    @SuppressLint(
        "MissingPermission"
    )
    fun startCapture(
        onFrame:
            (ByteArray) -> Unit
    ): Boolean {

        if (
            captureJob?.isActive ==
            true
        ) {
            return true
        }

        val minimumBuffer =
            AudioRecord
                .getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat
                        .CHANNEL_IN_MONO,
                    AudioFormat
                        .ENCODING_PCM_16BIT
                )

        if (
            minimumBuffer <=
            0
        ) {
            return false
        }

        val bufferSize =
            max(
                minimumBuffer * 2,

                FRAME_SAMPLES *
                    Short.SIZE_BYTES *
                    4
            )

        val newRecorder =
            try {

                AudioRecord(
                    MediaRecorder
                        .AudioSource
                        .VOICE_COMMUNICATION,

                    SAMPLE_RATE,

                    AudioFormat
                        .CHANNEL_IN_MONO,

                    AudioFormat
                        .ENCODING_PCM_16BIT,

                    bufferSize
                )

            } catch (_: Exception) {
                return false
            }

        if (
            newRecorder.state !=
            AudioRecord
                .STATE_INITIALIZED
        ) {

            newRecorder.release()

            return false
        }

        try {
            newRecorder
                .startRecording()
        } catch (_: Exception) {

            newRecorder.release()

            return false
        }

        if (
            newRecorder
                .recordingState !=
            AudioRecord
                .RECORDSTATE_RECORDING
        ) {

            newRecorder.release()

            return false
        }

        recorder =
            newRecorder

        encoderStepIndex =
            0

        voiceFilter.reset()

        attachCaptureEffects(
            newRecorder.audioSessionId
        )

        captureJob =
            scope.launch {

                val frame =
                    ShortArray(
                        FRAME_SAMPLES
                    )

                while (
                    isActive &&
                    recorder ===
                        newRecorder
                ) {

                    var filled =
                        0

                    while (
                        filled <
                            FRAME_SAMPLES &&
                        isActive
                    ) {

                        val read =
                            try {

                                newRecorder
                                    .read(
                                        frame,
                                        filled,

                                        FRAME_SAMPLES -
                                            filled,

                                        AudioRecord
                                            .READ_BLOCKING
                                    )

                            } catch (_: Exception) {
                                break
                            }

                        if (
                            read <=
                            0
                        ) {
                            break
                        }

                        filled +=
                            read
                    }

                    if (
                        filled !=
                        FRAME_SAMPLES
                    ) {
                        continue
                    }

                    voiceFilter.process(frame)

                    val encoded =
                        ImaAdpcm.encode(
                            samples =
                                frame,
                            initialStepIndex =
                                encoderStepIndex
                        )

                    encoderStepIndex =
                        encoded
                            .endingStepIndex

                    onFrame(
                        encoded.bytes
                    )
                }
            }

        return true
    }

    fun stopCapture() {

        val current =
            recorder

        recorder =
            null

        try {
            current?.stop()
        } catch (_: Exception) {
        }

        captureJob?.cancel()

        captureJob =
            null

        releaseCaptureEffects()

        try {
            current?.release()
        } catch (_: Exception) {
        }

        encoderStepIndex =
            0
    }

    private fun attachCaptureEffects(
        audioSessionId: Int
    ) {

        releaseCaptureEffects()

        try {
            if (
                NoiseSuppressor.isAvailable()
            ) {
                noiseSuppressor =
                    NoiseSuppressor
                        .create(audioSessionId)
                        ?.also {
                                effect ->

                            effect.setEnabled(true)
                        }
            }
        } catch (_: Exception) {
            noiseSuppressor = null
        }

        try {
            if (
                AutomaticGainControl.isAvailable()
            ) {
                automaticGainControl =
                    AutomaticGainControl
                        .create(audioSessionId)
                        ?.also {
                                effect ->

                            effect.setEnabled(true)
                        }
            }
        } catch (_: Exception) {
            automaticGainControl = null
        }
    }

    private fun releaseCaptureEffects() {

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }

        try {
            automaticGainControl?.release()
        } catch (_: Exception) {
        }

        noiseSuppressor = null
        automaticGainControl = null
    }

    fun beginRemoteSession(
        sessionId: Long,
        firstSequence: Int = 1
    ) {

        synchronized(
            receiveLock
        ) {

            val existing =
                receiveSession

            if (
                existing?.sessionId ==
                sessionId
            ) {
                return
            }

            receiveSession =
                createReceiveSession(
                    sessionId =
                        sessionId,
                    firstSequence =
                        firstSequence
                            .coerceAtLeast(
                                1
                            )
                )
        }
    }

    fun queueRemoteFrame(
        sessionId: Long,
        sequence: Int,
        encoded: ByteArray
    ) {

        synchronized(
            receiveLock
        ) {

            var session =
                receiveSession

            if (
                session == null ||
                session.sessionId != sessionId
            ) {

                session =
                    createReceiveSession(
                        sessionId =
                            sessionId,
                        firstSequence =
                            sequence
                                .coerceAtLeast(1)
                    )

                receiveSession =
                    session
            }

            val now =
                SystemClock
                    .elapsedRealtime()

            if (
                sequence <
                session.expectedSequence
            ) {

                MeshDiagnostics
                    .recordLateFrameDropped()

                growJitterTarget(
                    session,
                    now
                )

                return
            }

            session.lastActivityMs =
                now

            if (
                session.frames.size >=
                MAX_JITTER_FRAMES
            ) {

                val largest =
                    session.frames
                        .lastKey()

                if (
                    largest > sequence
                ) {
                    session.frames
                        .remove(largest)
                } else {
                    return
                }
            }

            session.frames
                .putIfAbsent(
                    sequence,
                    encoded
                )
        }
    }

    private fun growJitterTarget(
        session: ReceiveSession,
        now: Long
    ) {

        lastJitterIssueMs =
            now

        if (
            now - lastTargetChangeMs <
            TARGET_CHANGE_INTERVAL_MS
        ) {
            return
        }

        val raised =
            (
                    adaptiveTargetMs +
                            FRAME_DURATION_MS
                    )
                .coerceAtMost(
                    MAX_JITTER_TARGET_MS
                )

        if (
            raised == adaptiveTargetMs
        ) {
            return
        }

        adaptiveTargetMs =
            raised

        lastTargetChangeMs =
            now

        session.nextPlayoutMs +=
            FRAME_DURATION_MS

        MeshDiagnostics
            .updateJitterTarget(
                raised.toInt()
            )
    }

    private fun targetFrames():
            Int =
        (
                (
                        adaptiveTargetMs +
                                FRAME_DURATION_MS - 1
                        ) /
                        FRAME_DURATION_MS
                ).toInt()

    fun endRemoteSession(
        sessionId: Long,
        endSequence: Int
    ) {

        synchronized(
            receiveLock
        ) {

            val session =
                receiveSession

            if (
                session?.sessionId !=
                sessionId
            ) {
                return
            }

            session.endSequence =
                endSequence

            session.lastActivityMs =
                SystemClock
                    .elapsedRealtime()
        }
    }

    fun clearRemoteReception() {

        synchronized(
            receiveLock
        ) {
            receiveSession =
                null
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }
    }

    private fun createReceiveSession(
        sessionId: Long,
        firstSequence: Int
    ): ReceiveSession {

        val now =
            SystemClock
                .elapsedRealtime()

        if (
            adaptiveTargetMs >
            MIN_JITTER_TARGET_MS &&
            now - lastJitterIssueMs >
            SHRINK_AFTER_STABLE_MS &&
            now - lastTargetChangeMs >
            SHRINK_AFTER_STABLE_MS
        ) {

            adaptiveTargetMs =
                (
                        adaptiveTargetMs -
                                FRAME_DURATION_MS
                        )
                    .coerceAtLeast(
                        MIN_JITTER_TARGET_MS
                    )

            lastTargetChangeMs =
                now
        }

        MeshDiagnostics
            .updateJitterTarget(
                adaptiveTargetMs.toInt()
            )

        return ReceiveSession(
            sessionId =
                sessionId,
            expectedSequence =
                firstSequence,
            nextPlayoutMs =
                now + adaptiveTargetMs,
            lastActivityMs =
                now
        )
    }

    private fun startPlayoutWorker() {

        playoutJob =
            scope.launch {

                while (
                    isActive
                ) {

                    val now =
                        SystemClock
                            .elapsedRealtime()

                    val decision =
                        synchronized(
                            receiveLock
                        ) {

                            createPlayoutDecision(
                                now
                            )
                        }

                    if (
                        decision !=
                        null
                    ) {

                        processPlayoutDecision(
                            decision
                        )
                    }

                    delay(
                        PLAYOUT_CHECK_MS
                    )
                }
            }
    }

    private fun createPlayoutDecision(
        now: Long
    ): PlayoutDecision? {

        val session =
            receiveSession
                ?: return null

        if (
            now - session.lastActivityMs >
            RECEIVE_IDLE_TIMEOUT_MS &&
            session.frames.isEmpty()
        ) {

            receiveSession =
                null

            return null
        }

        if (
            now < session.nextPlayoutMs
        ) {
            return null
        }

        val excess =
            session.frames.size -
                    targetFrames()

        if (
            excess > TRIM_SLACK_FRAMES &&
            now - session.lastTrimMs >=
            TRIM_INTERVAL_MS
        ) {

            session.frames
                .remove(
                    session.expectedSequence
                )

            session.expectedSequence++

            session.lastTrimMs =
                now

            MeshDiagnostics
                .recordFrameTrimmed()
        }

        val endSequence =
            session.endSequence

        if (
            endSequence != null &&
            session.expectedSequence >=
            endSequence
        ) {

            receiveSession =
                null

            return null
        }

        val sequence =
            session.expectedSequence

        val encoded =
            session.frames
                .remove(sequence)

        session.expectedSequence++

        session.nextPlayoutMs +=
            FRAME_DURATION_MS

        if (
            encoded != null
        ) {

            session.consecutiveLosses =
                0

            return PlayoutDecision(
                sessionId =
                    session.sessionId,
                encoded =
                    encoded,
                previousPcm =
                    session.lastGoodPcm,
                lossNumber =
                    0
            )
        }

        session.consecutiveLosses++

        lastJitterIssueMs =
            now

        return PlayoutDecision(
            sessionId =
                session.sessionId,
            encoded =
                null,
            previousPcm =
                session.lastGoodPcm
                    ?.copyOf(),
            lossNumber =
                session.consecutiveLosses
        )
    }

    private fun processPlayoutDecision(
        decision:
            PlayoutDecision
    ) {

        val pcm =
            if (
                decision.encoded !=
                null
            ) {

                ImaAdpcm.decode(
                    decision.encoded
                )

            } else {

                concealLostFrame(
                    previous =
                        decision.previousPcm,

                    lossNumber =
                        decision.lossNumber
                )
            }

        if (
            pcm == null
        ) {

            MeshDiagnostics
                .recordVoiceFrameLost()

            return
        }

        if (
            decision.encoded !=
            null
        ) {

            MeshDiagnostics
                .recordVoiceFrameReceived()

            synchronized(
                receiveLock
            ) {

                val session =
                    receiveSession

                if (
                    session?.sessionId ==
                    decision.sessionId
                ) {

                    session.lastGoodPcm =
                        pcm.copyOf()
                }
            }

        } else {

            MeshDiagnostics
                .recordVoiceFrameLost()
        }

        writePcm(
            pcm
        )
    }

    private fun concealLostFrame(
        previous: ShortArray?,
        lossNumber: Int
    ): ShortArray {

        if (
            previous == null ||
            lossNumber >=
                5
        ) {

            return ShortArray(
                FRAME_SAMPLES
            )
        }

        val startingGain =
            when (
                lossNumber
            ) {

                1 -> 0.78
                2 -> 0.58
                3 -> 0.38
                4 -> 0.20

                else -> 0.0
            }

        val output =
            ShortArray(
                previous.size
            )

        val denominator =
            max(
                previous.size - 1,
                1
            )

        for (
            index in
            previous.indices
        ) {

            val position =
                index.toDouble() /
                    denominator.toDouble()

            val gain =
                startingGain *
                    (
                        1.0 -
                            0.15 *
                            position
                        )

            output[index] =
                (
                    previous[index] *
                        gain
                    )
                    .toInt()
                    .coerceIn(
                        Short.MIN_VALUE
                            .toInt(),

                        Short.MAX_VALUE
                            .toInt()
                    )
                    .toShort()
        }

        return output
    }

    private fun writePcm(
        pcm: ShortArray
    ) {

        val player =
            ensureAudioTrack()
                ?: return

        if (
            player.playState !=
            AudioTrack
                .PLAYSTATE_PLAYING
        ) {

            try {
                player.play()
            } catch (_: Exception) {
                return
            }
        }

        try {

            player.write(
                pcm,
                0,
                pcm.size,
                AudioTrack
                    .WRITE_BLOCKING
            )

        } catch (_: Exception) {
        }
    }

    private fun ensureAudioTrack():
        AudioTrack? {

        audioTrack?.let {
            track ->

            if (
                track.state ==
                AudioTrack
                    .STATE_INITIALIZED
            ) {
                return track
            }
        }

        val minimum =
            AudioTrack
                .getMinBufferSize(
                    SAMPLE_RATE,

                    AudioFormat
                        .CHANNEL_OUT_MONO,

                    AudioFormat
                        .ENCODING_PCM_16BIT
                )

        if (
            minimum <=
            0
        ) {
            return null
        }

        val track =
            try {

                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(
                                AudioAttributes
                                    .USAGE_MEDIA
                            )
                            .setContentType(
                                AudioAttributes
                                    .CONTENT_TYPE_SPEECH
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(
                                AudioFormat
                                    .ENCODING_PCM_16BIT
                            )
                            .setSampleRate(
                                SAMPLE_RATE
                            )
                            .setChannelMask(
                                AudioFormat
                                    .CHANNEL_OUT_MONO
                            )
                            .build()
                    )
                    .setTransferMode(
                        AudioTrack
                            .MODE_STREAM
                    )
                    .setBufferSizeInBytes(
                        max(
                            minimum * 4,

                            FRAME_SAMPLES *
                                Short.SIZE_BYTES *
                                8
                        )
                    )
                    .build()

            } catch (_: Exception) {
                return null
            }

        if (
            track.state !=
            AudioTrack
                .STATE_INITIALIZED
        ) {

            track.release()

            return null
        }

        audioTrack =
            track

        return track
    }

    fun shutdown() {

        stopCapture()

        synchronized(
            receiveLock
        ) {
            receiveSession =
                null
        }

        playoutJob?.cancel()
        playoutJob = null

        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack =
            null

        scope.cancel()
    }

    private data class ReceiveSession(
        val sessionId: Long,
        var expectedSequence: Int,
        var nextPlayoutMs: Long,
        var lastActivityMs: Long,
        var endSequence: Int? = null,
        var consecutiveLosses: Int = 0,
        var lastTrimMs: Long = 0L,
        var lastGoodPcm: ShortArray? = null,
        val frames: TreeMap<Int, ByteArray> = TreeMap()
    )

    private data class PlayoutDecision(
        val sessionId: Long,
        val encoded: ByteArray?,
        val previousPcm:
            ShortArray?,
        val lossNumber: Int
    )

    companion object {

        const val SAMPLE_RATE =
            8_000

        const val FRAME_SAMPLES =
            320

        const val FRAME_DURATION_MS =
            40L

        /*
         * Three 40 ms frames of
         * buffering.
         */
        private const val JITTER_TARGET_MS =
            120L

        private const val MIN_JITTER_TARGET_MS =
            80L

        private const val MAX_JITTER_TARGET_MS =
            400L

        private const val TARGET_CHANGE_INTERVAL_MS =
            500L

        private const val SHRINK_AFTER_STABLE_MS =
            15_000L

        private const val TRIM_SLACK_FRAMES =
            2

        private const val TRIM_INTERVAL_MS =
            160L

        private const val PLAYOUT_CHECK_MS =
            5L

        private const val RECEIVE_IDLE_TIMEOUT_MS =
            2_000L

        private const val MAX_JITTER_FRAMES =
            32
    }
}
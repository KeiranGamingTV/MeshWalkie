package com.keiranhaas.bluetoothwalkie.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

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

    private val playbackQueue =
        Channel<ByteArray>(
            capacity = 128
        )

    @Volatile
    private var recorder:
        AudioRecord? = null

    private var captureJob:
        Job? = null

    private var playbackJob:
        Job? = null

    private var audioTrack:
        AudioTrack? = null

    private var encoderStepIndex =
        0

    init {
        startPlaybackWorker()
    }

    @SuppressLint("MissingPermission")
    fun startCapture(
        onFrame: (
            ByteArray
        ) -> Unit
    ): Boolean {

        if (
            captureJob?.isActive ==
            true
        ) {
            return true
        }

        val minimumBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (
            minimumBuffer <= 0
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
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (_: Exception) {
                return false
            }

        if (
            newRecorder.state !=
            AudioRecord.STATE_INITIALIZED
        ) {
            newRecorder.release()
            return false
        }

        try {
            newRecorder.startRecording()
        } catch (_: Exception) {
            newRecorder.release()
            return false
        }

        if (
            newRecorder.recordingState !=
            AudioRecord.RECORDSTATE_RECORDING
        ) {
            newRecorder.release()
            return false
        }

        recorder =
            newRecorder

        encoderStepIndex =
            0

        captureJob =
            scope.launch {

                val frame =
                    ShortArray(
                        FRAME_SAMPLES
                    )

                while (
                    isActive &&
                    recorder === newRecorder
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
                                newRecorder.read(
                                    frame,
                                    filled,
                                    FRAME_SAMPLES -
                                        filled,
                                    AudioRecord.READ_BLOCKING
                                )
                            } catch (_: Exception) {
                                break
                            }

                        if (
                            read <= 0
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

        recorder = null

        try {
            current?.stop()
        } catch (_: Exception) {
        }

        captureJob?.cancel()

        captureJob = null

        try {
            current?.release()
        } catch (_: Exception) {
        }

        encoderStepIndex =
            0
    }

    fun playCompressedFrame(
        frame: ByteArray
    ) {
        playbackQueue
            .trySend(
                frame
            )
    }

    private fun startPlaybackWorker() {

        playbackJob =
            scope.launch {

                for (
                    encoded in
                    playbackQueue
                ) {

                    val pcm =
                        ImaAdpcm.decode(
                            encoded
                        )
                            ?: continue

                    val player =
                        ensureAudioTrack()
                            ?: continue

                    if (
                        player.playState !=
                        AudioTrack.PLAYSTATE_PLAYING
                    ) {
                        try {
                            player.play()
                        } catch (_: Exception) {
                            continue
                        }
                    }

                    try {
                        player.write(
                            pcm,
                            0,
                            pcm.size,
                            AudioTrack.WRITE_BLOCKING
                        )
                    } catch (_: Exception) {
                    }
                }
            }
    }

    private fun ensureAudioTrack():
        AudioTrack? {

        audioTrack?.let {
            if (
                it.state ==
                AudioTrack.STATE_INITIALIZED
            ) {
                return it
            }
        }

        val minimum =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (
            minimum <= 0
        ) {
            return null
        }

        val track =
            try {

                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            .setSampleRate(
                                SAMPLE_RATE
                            )
                            .setChannelMask(
                                AudioFormat.CHANNEL_OUT_MONO
                            )
                            .build()
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
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
            AudioTrack.STATE_INITIALIZED
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

        playbackQueue.close()

        playbackJob?.cancel()

        playbackJob = null

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

        audioTrack = null

        scope.cancel()
    }

    companion object {

        const val SAMPLE_RATE =
            8_000

        /*
         * 320 samples / 8000 Hz
         * = 40 ms per packet.
         *
         * That reduces packet overhead
         * substantially compared with
         * 20 ms frames.
         */
        const val FRAME_SAMPLES =
            320
    }
}
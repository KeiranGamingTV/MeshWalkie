package com.keiranhaas.bluetoothwalkie.audio

data class AdpcmEncodedFrame(
    val bytes: ByteArray,
    val endingStepIndex: Int
)

object ImaAdpcm {

    fun encode(
        samples: ShortArray,
        initialStepIndex: Int = 0
    ): AdpcmEncodedFrame {

        if (samples.isEmpty()) {
            return AdpcmEncodedFrame(
                bytes = ByteArray(0),
                endingStepIndex =
                    initialStepIndex
            )
        }

        var predictor =
            samples[0].toInt()

        var stepIndex =
            initialStepIndex
                .coerceIn(
                    0,
                    STEP_TABLE.lastIndex
                )

        val encodedSamples =
            samples.size - 1

        val encodedBytes =
            (encodedSamples + 1) / 2

        val output =
            ByteArray(
                HEADER_SIZE +
                    encodedBytes
            )

        // Initial predictor:
        output[0] =
            (predictor and 0xFF)
                .toByte()

        output[1] =
            ((predictor shr 8) and 0xFF)
                .toByte()

        // Initial step-table index:
        output[2] =
            stepIndex.toByte()

        // Original PCM sample count:
        output[3] =
            (samples.size and 0xFF)
                .toByte()

        output[4] =
            ((samples.size shr 8) and 0xFF)
                .toByte()

        var outputIndex =
            HEADER_SIZE

        var lowNibblePending =
            false

        var packedByte =
            0

        for (
            sampleIndex in
            1 until samples.size
        ) {

            val sample =
                samples[
                    sampleIndex
                ].toInt()

            val step =
                STEP_TABLE[
                    stepIndex
                ]

            var difference =
                sample - predictor

            var code =
                0

            if (
                difference < 0
            ) {
                code =
                    code or 8

                difference =
                    -difference
            }

            var delta =
                step shr 3

            if (
                difference >= step
            ) {
                code =
                    code or 4

                difference -=
                    step

                delta +=
                    step
            }

            if (
                difference >=
                (step shr 1)
            ) {
                code =
                    code or 2

                difference -=
                    step shr 1

                delta +=
                    step shr 1
            }

            if (
                difference >=
                (step shr 2)
            ) {
                code =
                    code or 1

                delta +=
                    step shr 2
            }

            predictor +=
                if (
                    code and 8 != 0
                ) {
                    -delta
                } else {
                    delta
                }

            predictor =
                predictor.coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt()
                )

            stepIndex +=
                INDEX_TABLE[
                    code and 0x0F
                ]

            stepIndex =
                stepIndex.coerceIn(
                    0,
                    STEP_TABLE.lastIndex
                )

            if (
                !lowNibblePending
            ) {

                packedByte =
                    code and 0x0F

                lowNibblePending =
                    true

            } else {

                packedByte =
                    packedByte or
                        ((code and 0x0F) shl 4)

                output[
                    outputIndex++
                ] =
                    packedByte.toByte()

                lowNibblePending =
                    false
            }
        }

        if (
            lowNibblePending
        ) {
            output[
                outputIndex
            ] =
                packedByte.toByte()
        }

        return AdpcmEncodedFrame(
            bytes = output,
            endingStepIndex =
                stepIndex
        )
    }

    fun decode(
        encoded: ByteArray
    ): ShortArray? {

        if (
            encoded.size <
            HEADER_SIZE
        ) {
            return null
        }

        var predictor =
            (encoded[0].toInt() and 0xFF) or
                (
                    (encoded[1].toInt() and 0xFF)
                        shl 8
                    )

        if (
            predictor and 0x8000 != 0
        ) {
            predictor -=
                0x10000
        }

        var stepIndex =
            encoded[2]
                .toInt() and 0xFF

        stepIndex =
            stepIndex.coerceIn(
                0,
                STEP_TABLE.lastIndex
            )

        val sampleCount =
            (encoded[3].toInt() and 0xFF) or
                (
                    (encoded[4].toInt() and 0xFF)
                        shl 8
                    )

        if (
            sampleCount <= 0 ||
            sampleCount >
            MAX_SAMPLES_PER_FRAME
        ) {
            return null
        }

        val requiredNibbles =
            sampleCount - 1

        val availableNibbles =
            (encoded.size - HEADER_SIZE) *
                2

        if (
            availableNibbles <
            requiredNibbles
        ) {
            return null
        }

        val output =
            ShortArray(
                sampleCount
            )

        output[0] =
            predictor.toShort()

        var byteIndex =
            HEADER_SIZE

        for (
            sampleIndex in
            1 until sampleCount
        ) {

            val packed =
                encoded[
                    byteIndex +
                        ((sampleIndex - 1) / 2)
                ].toInt() and 0xFF

            val code =
                if (
                    (sampleIndex - 1) % 2 == 0
                ) {
                    packed and 0x0F
                } else {
                    (packed shr 4) and 0x0F
                }

            val step =
                STEP_TABLE[
                    stepIndex
                ]

            var delta =
                step shr 3

            if (
                code and 4 != 0
            ) {
                delta +=
                    step
            }

            if (
                code and 2 != 0
            ) {
                delta +=
                    step shr 1
            }

            if (
                code and 1 != 0
            ) {
                delta +=
                    step shr 2
            }

            predictor +=
                if (
                    code and 8 != 0
                ) {
                    -delta
                } else {
                    delta
                }

            predictor =
                predictor.coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt()
                )

            stepIndex +=
                INDEX_TABLE[
                    code
                ]

            stepIndex =
                stepIndex.coerceIn(
                    0,
                    STEP_TABLE.lastIndex
                )

            output[
                sampleIndex
            ] =
                predictor.toShort()
        }

        return output
    }

    private const val HEADER_SIZE =
        5

    private const val MAX_SAMPLES_PER_FRAME =
        2048

    private val INDEX_TABLE =
        intArrayOf(
            -1, -1, -1, -1,
            2, 4, 6, 8,
            -1, -1, -1, -1,
            2, 4, 6, 8
        )

    private val STEP_TABLE =
        intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14,
            16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66,
            73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411,
            1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
            3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
            7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
            32767
        )
}
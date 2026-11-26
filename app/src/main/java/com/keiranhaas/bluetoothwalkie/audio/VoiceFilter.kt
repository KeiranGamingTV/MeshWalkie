package com.keiranhaas.bluetoothwalkie.audio

class VoiceFilter {

    private var previousInput = 0f
    private var previousOutput = 0f

    fun reset() {
        previousInput = 0f
        previousOutput = 0f
    }

    fun process(samples: ShortArray) {

        for (index in samples.indices) {

            val input = samples[index].toFloat()

            val output =
                COEFFICIENT *
                        (previousOutput + input - previousInput)

            previousInput = input
            previousOutput = output

            samples[index] =
                output
                    .coerceIn(-32768f, 32767f)
                    .toInt()
                    .toShort()
        }
    }

    companion object {
        private const val COEFFICIENT = 0.927f
    }
}
package com.bydassistantng.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A second-order filter section (RBJ cookbook, Butterworth Q), in transposed direct form II so it keeps its state between windows. */
internal class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        private const val Q = 0.70710678

        fun lowPass(cutoffHz: Double, sampleRate: Int): Biquad {
            val w = 2 * PI * cutoffHz / sampleRate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(cutoffHz: Double, sampleRate: Int): Biquad {
            val w = 2 * PI * cutoffHz / sampleRate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

/**
 * The part of the cabin noise that gets in the way of speech: what is left of the microphone's signal between
 * [LOW_HZ] and [HIGH_HZ]. A car's road and wind noise is mostly low rumble, which is loud on a plain level meter yet
 * hides a voice far less than the hiss of a fan or the body of music does; reading the level of this band keeps a
 * reply from being turned up for a rumble it does not need to beat.
 */
internal class SpeechBand(sampleRate: Int) {
    private val highPass = Biquad.highPass(LOW_HZ, sampleRate)
    private val lowPass = Biquad.lowPass(HIGH_HZ, sampleRate)

    /** The level (dBFS) of [samples] once filtered to the speech band; the filters carry on from the previous window. */
    fun dbfs(samples: ShortArray): Double {
        if (samples.isEmpty()) return CabinNoise.SILENCE_DBFS
        var sum = 0.0
        for (s in samples) {
            val y = lowPass.process(highPass.process(s.toDouble()))
            sum += y * y
        }
        return CabinNoise.toDbfs(sqrt(sum / samples.size) / Short.MAX_VALUE)
    }

    fun reset() {
        highPass.reset()
        lowPass.reset()
    }

    companion object {
        const val LOW_HZ = 300.0
        const val HIGH_HZ = 3400.0
    }
}

/** What one window of the microphone says: its whole-band level, and the level of its speech band (already matched to the whole-band scale by [CabinNoise.SPEECH_BAND_OFFSET_DB]). */
internal class WindowLevels(val whole: Double, val speech: Double)

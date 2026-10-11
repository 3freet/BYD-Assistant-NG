package com.bydassistantng.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** A short two-note chime, generated in code: the sound used to try the exterior speaker. */
internal object OutsideTone {
    const val SAMPLE_RATE = 48_000

    private const val FIRST_HZ = 880.0
    private const val SECOND_HZ = 659.25
    private const val FADE_IN_S = 0.01
    private const val FADE_OUT_S = 0.03

    private const val WARBLE_S = 0.5

    /**
     * Mono 16-bit PCM that does not fade: [FIRST_HZ] and [SECOND_HZ] alternating every half second at a
     * constant level, with short fades only at the very start and end. For hearing, without doubt, that
     * the sound lasts as long as asked.
     */
    fun steady(durationMs: Int, amplitude: Double): ShortArray {
        val peak = amplitude.coerceIn(0.0, 1.0)
        val count = SAMPLE_RATE * durationMs / 1000
        val warble = (SAMPLE_RATE * WARBLE_S).toInt()
        val out = ShortArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val frequency = if ((i / warble) % 2 == 0) FIRST_HZ else SECOND_HZ
            phase += 2 * PI * frequency / SAMPLE_RATE
            val t = i.toDouble() / SAMPLE_RATE
            val fadeIn = (t / FADE_IN_S).coerceAtMost(1.0)
            val fadeOut = ((count - i).toDouble() / SAMPLE_RATE / FADE_OUT_S).coerceAtMost(1.0)
            val sample = sin(phase) * peak * fadeIn * fadeOut
            out[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * Mono 16-bit PCM of about [durationMs]: [FIRST_HZ] for the first half, [SECOND_HZ] for the second,
     * each ringing out, with short fades so there is no click. [amplitude] (0..1) is the peak as a
     * share of full scale.
     */
    fun chime(durationMs: Int, amplitude: Double): ShortArray {
        val peak = amplitude.coerceIn(0.0, 1.0)
        val count = SAMPLE_RATE * durationMs / 1000
        val half = count / 2
        val out = ShortArray(count)
        for (i in 0 until count) {
            val local = if (i < half) i else i - half
            val length = if (i < half) half else count - half
            val t = local.toDouble() / SAMPLE_RATE
            val frequency = if (i < half) FIRST_HZ else SECOND_HZ
            val tau = length.toDouble() / SAMPLE_RATE / 2.5
            val wave = sin(2 * PI * frequency * t) + 0.25 * sin(2 * PI * frequency * 2 * t)
            val fadeIn = (t / FADE_IN_S).coerceAtMost(1.0)
            val fadeOut = ((length - local).toDouble() / SAMPLE_RATE / FADE_OUT_S).coerceAtMost(1.0)
            val sample = wave / 1.25 * peak * fadeIn * fadeOut * exp(-t / tau)
            out[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

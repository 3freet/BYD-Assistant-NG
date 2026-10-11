package com.bydassistantng.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The ready-made sound effects for the exterior speaker, made in code (no recordings are bundled): rough
 * synthesized stand-ins, 24 kHz mono 16-bit, each scaled to the same peak so the volume setting means the
 * same for all of them. No emergency-vehicle sirens: sounding like one outside a car is not something
 * an assistant should do.
 */
internal object OutsideEffects {
    /** The Live audio's rate, so an effect can go through the same player path as a reply. */
    const val SAMPLE_RATE = 24_000

    /** Every effect is scaled to this share of full scale. */
    private const val PEAK = 0.9

    val catalog: List<SoundEffect> = listOf(
        SoundEffect("doorbell", "a two-note ding-dong"),
        SoundEffect("chime", "a soft two-note chime"),
        SoundEffect("bell", "a single bell strike that rings out"),
        SoundEffect("beep", "three short beeps"),
        SoundEffect("alarm", "an alarm-clock style buzzer"),
        SoundEffect("horn", "a short car-horn honk"),
        SoundEffect("whistle", "a rising and falling whistle"),
        SoundEffect("bird", "bird chirps"),
        SoundEffect("cat", "a cat meowing"),
    )

    /** The effect named [name], or null when there is none. */
    fun render(name: String): ShortArray? {
        val samples = when (name.lowercase()) {
            "doorbell" -> doorbell()
            "chime" -> chime()
            "bell" -> bell()
            "beep" -> beep()
            "alarm" -> alarm()
            "horn" -> horn()
            "whistle" -> whistle()
            "bird" -> bird()
            "cat" -> meow()
            else -> return null
        }
        return finish(samples)
    }

    private fun count(ms: Int) = SAMPLE_RATE * ms / 1000

    /** A struck, decaying note: the fundamental and some harmonics, ringing out over [tauMs]. */
    private fun note(frequency: Double, ms: Int, tauMs: Double, harmonics: DoubleArray = doubleArrayOf(1.0, 0.35, 0.12)): FloatArray {
        val length = count(ms)
        val tau = tauMs / 1000.0
        return FloatArray(length) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            var wave = 0.0
            for ((k, amplitude) in harmonics.withIndex()) wave += amplitude * sin(2 * PI * frequency * (k + 1) * t)
            val attack = min(1.0, t / 0.004)
            val release = min(1.0, (length - i).toDouble() / (SAMPLE_RATE * 0.02))
            (wave * attack * release * exp(-t / tau)).toFloat()
        }
    }

    /** A steady (square-ish) tone with short fades, for beeps and buzzers. */
    private fun buzz(frequency: Double, ms: Int): FloatArray {
        val length = count(ms)
        return FloatArray(length) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val wave = sin(2 * PI * frequency * t) + sin(2 * PI * frequency * 3 * t) / 3 + sin(2 * PI * frequency * 5 * t) / 5
            val fade = min(min(1.0, t / 0.005), (length - i).toDouble() / (SAMPLE_RATE * 0.005))
            (wave * fade * 0.7).toFloat()
        }
    }

    private fun silence(ms: Int) = FloatArray(count(ms))

    private fun join(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /** Adds [part] into [target] starting at [atMs]. */
    private fun mix(target: FloatArray, part: FloatArray, atMs: Int) {
        val start = count(atMs)
        for (i in part.indices) if (start + i < target.size) target[start + i] += part[i]
    }

    private fun doorbell(): FloatArray {
        val out = FloatArray(count(1700))
        mix(out, note(659.25, 700, 280.0), 0)
        mix(out, note(523.25, 1250, 420.0), 450)
        return out
    }

    private fun chime(): FloatArray {
        val out = FloatArray(count(1100))
        mix(out, note(880.0, 500, 200.0), 0)
        mix(out, note(1318.51, 800, 300.0), 230)
        return out
    }

    private fun bell(): FloatArray {
        val base = 480.0
        val partials = doubleArrayOf(0.5, 1.0, 1.183, 1.506, 2.0, 2.514, 2.662, 3.011)
        val amplitudes = doubleArrayOf(0.5, 1.0, 0.6, 0.5, 0.4, 0.3, 0.25, 0.2)
        val length = count(2400)
        return FloatArray(length) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            var wave = 0.0
            for (k in partials.indices) {
                val tau = 1.5 / (1.0 + 0.6 * k)
                wave += amplitudes[k] * sin(2 * PI * base * partials[k] * t) * exp(-t / tau)
            }
            val attack = min(1.0, t / 0.002)
            val release = min(1.0, (length - i).toDouble() / (SAMPLE_RATE * 0.03))
            (wave * attack * release).toFloat()
        }
    }

    private fun beep(): FloatArray = join(
        buzz(1000.0, 110), silence(100), buzz(1000.0, 110), silence(100), buzz(1000.0, 110),
    )

    private fun alarm(): FloatArray {
        val burst = join(
            buzz(2000.0, 90), silence(60), buzz(2000.0, 90), silence(60), buzz(2000.0, 90), silence(60), buzz(2000.0, 90),
        )
        return join(burst, silence(350), burst, silence(350), burst)
    }

    private fun horn(): FloatArray {
        val length = count(750)
        return FloatArray(length) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            var wave = 0.0
            // Two reed tones a third apart, slightly out of tune with each other, each with a buzzy set of harmonics.
            for (k in 1..6) {
                wave += sin(2 * PI * 415.0 * k * t) / k
                wave += sin(2 * PI * (523.0 + 3.0) * k * t) / k
            }
            val attack = min(1.0, t / 0.025)
            val release = min(1.0, (length - i).toDouble() / (SAMPLE_RATE * 0.12))
            (wave * attack * release).toFloat()
        }
    }

    private fun whistle(): FloatArray {
        val length = count(1150)
        var phase = 0.0
        var noise = 12345L
        return FloatArray(length) { i ->
            val t = i.toDouble() / length
            val pitch = when {
                t < 0.4 -> 1400 + 900 * (t / 0.4)
                t < 0.62 -> 2300.0
                else -> 2300 - 600 * ((t - 0.62) / 0.38)
            } + 35 * sin(2 * PI * 5.5 * i / SAMPLE_RATE)
            phase += 2 * PI * pitch / SAMPLE_RATE
            noise = noise * 1103515245 + 12345
            val breath = ((noise shr 16) and 0x7FFF) / 16384.0 - 1.0
            val attack = min(1.0, i.toDouble() / (SAMPLE_RATE * 0.04))
            val release = min(1.0, (length - i).toDouble() / (SAMPLE_RATE * 0.1))
            ((sin(phase) + 0.15 * sin(2 * phase) + 0.03 * breath) * attack * release).toFloat()
        }
    }

    private fun bird(): FloatArray {
        // (start, end) pitch of each chirp, in hertz.
        val chirps = listOf(2800.0 to 4200.0, 3000.0 to 4600.0, 2600.0 to 4000.0, 3200.0 to 4800.0, 2900.0 to 4300.0, 3400.0 to 5000.0)
        val parts = ArrayList<FloatArray>()
        for ((index, chirp) in chirps.withIndex()) {
            val length = count(95)
            var phase = 0.0
            parts += FloatArray(length) { i ->
                val t = i.toDouble() / length
                val pitch = chirp.first * Math.pow(chirp.second / chirp.first, t)
                phase += 2 * PI * pitch / SAMPLE_RATE
                val envelope = sin(PI * t).let { it * it }
                ((sin(phase) + 0.3 * sin(2 * phase)) * envelope).toFloat()
            }
            parts += silence(if (index % 3 == 2) 220 else 70)
        }
        return join(*parts.toTypedArray())
    }

    /** One band-pass resonator whose centre can move from sample to sample. */
    private class Resonator {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double, centre: Double, q: Double): Double {
            val w = 2 * PI * centre / SAMPLE_RATE
            val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha
            val y = (alpha / a0) * x - (alpha / a0) * x2 - (-2 * cos(w) / a0) * y1 - ((1 - alpha) / a0) * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    /** A meow: a buzzy source with a rising then falling pitch, run through two moving resonances ("ee-oo-ow"). */
    private fun meow(): FloatArray {
        val length = count(950)
        val first = Resonator()
        val second = Resonator()
        var phase = 0.0
        return FloatArray(length) { i ->
            val t = i.toDouble() / length
            val pitch = when {
                t < 0.25 -> 420 + 400 * (t / 0.25)
                t < 0.7 -> 820 + 60 * ((t - 0.25) / 0.45)
                else -> 880 - 500 * ((t - 0.7) / 0.3)
            } + 12 * sin(2 * PI * 6.5 * i / SAMPLE_RATE)
            phase += 2 * PI * pitch / SAMPLE_RATE
            val source = ((phase % (2 * PI)) / PI) - 1.0
            val f1 = when {
                t < 0.3 -> 600 + 400 * (t / 0.3)
                t < 0.65 -> 1000.0
                else -> 1000 - 350 * ((t - 0.65) / 0.35)
            }
            val f2 = when {
                t < 0.3 -> 1700 + 800 * (t / 0.3)
                t < 0.65 -> 2500.0
                else -> 2500 - 1100 * ((t - 0.65) / 0.35)
            }
            val voiced = 1.0 * first.process(source, f1, 6.0) + 0.7 * second.process(source, f2, 8.0)
            val attack = min(1.0, i.toDouble() / (SAMPLE_RATE * 0.05))
            val release = min(1.0, (length - i).toDouble() / (SAMPLE_RATE * 0.15))
            (voiced * attack * release).toFloat()
        }
    }

    /** Scales to [PEAK] of full scale, with a short final fade so nothing ends on a click. */
    private fun finish(samples: FloatArray): ShortArray {
        var peak = 0f
        for (s in samples) peak = maxOf(peak, kotlin.math.abs(s))
        val scale = if (peak > 0f) (PEAK * Short.MAX_VALUE / peak).toFloat() else 0f
        val fade = count(15)
        return ShortArray(samples.size) { i ->
            val tail = min(1.0, (samples.size - i).toDouble() / fade).toFloat()
            (samples[i] * scale * tail).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

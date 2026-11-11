package com.bydassistantng.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the cabin microphone's sound goes through on its way to the exterior speaker in a live broadcast ([LiveBroadcast]), one chunk of 10 ms
 * (160 samples at 16 kHz) at a time. It is the recipe of the exterior sound apps that do this well, and nothing is added to it: the voice goes out
 * at the level the microphone gave it, so it is the car's own volume that makes it loud.
 *
 *  1. **Peak limit**: a chunk whose peak is above [Settings.inputPeak] is scaled down to it.
 *  2. **High-pass** at 80 Hz (the audio processing module's own, in front of its echo control) and **echo cancellation** ([EchoCanceller]): the
 *     reference is what was sent out [Settings.referenceChunks] chunks ago, and the canceller is told the echo arrives [Settings.delayMs] later.
 *  3. **Dry mix**: when the canceller took a lot out of a loud chunk (less than 0.42 of its level is left), up to 18 % of the untouched chunk is mixed
 *     back, so that a hard cancellation does not leave the voice choppy.
 *  4. **Gain** ([gain], 1 = none, never more than [MAX_GAIN]).
 *  5. **Hum and whistle filters**: a high-pass at 200 Hz, a low-pass at 3200 Hz (and a second one at 3000 Hz while the chunk is loud), then a soft
 *     clip above 80 % of full scale.
 *  6. **Tail fade**: once the microphone has been quiet for 22 chunks (0.22 s) the sound is faded, 12 % a chunk, down to 12 % of itself (-18 dB),
 *     and it is back at full the moment the microphone is loud again. This is what stops the echo of the last word from ringing on in the pauses.
 *
 * The thresholds of the tail fade and of the dry mix are levels of the microphone in 16-bit units (speech is 5500 and up, quiet is 1600 and below),
 * which the apps this follows have on a microphone that gives a hot voice. This unit's gives a voice of 300 to 1700, so with those numbers as they
 * are the fade never opened and the voice went out 18 dB down. They are therefore **scaled to the voice the microphone actually gives** (see
 * [Settings.adaptiveLevels]): the loudest the microphone has been lately, followed up quickly and down slowly, against the level they were made for.
 * What came out is kept as the next chunks' reference. Time is counted in chunks, so it can be tested with synthetic signals; [echo] is null where
 * the native library is not there, and the chain then goes without the first two steps' echo work.
 */
internal class TalkChain(
    private val echo: EchoCanceller?,
    private val settings: Settings = Settings(),
) {
    class Settings(
        /** How long after a chunk is played its sound is expected at the microphone: the echo canceller's start-up hint, which it refines. */
        val delayMs: Int = 150,
        /** The reference of the echo canceller is the chunk that was sent out this many chunks ago (1..7). */
        val referenceChunks: Int = 2,
        val inputPeak: Int = 22_000,
        val dryMixFromRms: Double = 2_500.0,
        val dryMixTargetRatio: Double = 0.42,
        val dryMixMax: Double = 0.18,
        val highPassHz: Int = 200,
        val lowPassHz: Int = 3_200,
        val loudLowPassHz: Int = 3_000,
        val loudRms: Double = 10_000.0,
        val clipFrom: Int = 26_213,
        val clipSlope: Double = 0.15,
        val tailSpeechRms: Double = 5_500.0,
        val tailQuietRms: Double = 1_600.0,
        val tailQuietChunks: Int = 22,
        val tailDecay: Double = 0.88,
        val tailMinGain: Double = 0.12,
        /**
         * Whether the speech, quiet and dry-mix thresholds follow the microphone's own level. They were made for a voice of about
         * [referenceSpeechRms]; with this on, a microphone that gives less has them scaled down by the same ratio (never below
         * [speechFloorRms] worth of voice, or the cabin's noise would count as speech), and one that gives that much or more has them as they are.
         */
        val adaptiveLevels: Boolean = true,
        val referenceSpeechRms: Double = 8_000.0,
        val speechFloorRms: Double = 900.0,
    )

    /** Levels over a stretch of chunks, in dB below full scale (mean power), the lowest the tail fade went, and the voice level the thresholds follow. */
    class Stats(val microphoneDb: Double, val cleanedDb: Double, val sentDb: Double, val lowestTailGain: Double, val speechRms: Double)

    /** A gain on the voice, 1 = none. The apps this follows cap it at 5; nothing here goes past that. */
    @Volatile var gain = 1.0f

    /**
     * Whether music is playing in the cabin while the voice is sent out (the broadcast's "music inside": the microphone hears the music as well as the
     * voice, and sent out it comes back a half second later as an echo of the music). The fade then goes by what the voice is *above the background*:
     * only what is clearly louder than the quietest the microphone has lately been counts as voice, everything else is faded (to a deeper floor) after
     * the usual 0.22 s, and there is no band in between in which the gain is held.
     */
    @Volatile var musicBackground = false

    /** What the echo canceller made of the last chunk (before the dry mix and the rest): for the debug recording. */
    @Volatile var lastCleaned = ShortArray(BROADCAST_CHUNK_SAMPLES)
        private set

    /** The levels of the last chunk, as RMS in 16-bit units: the microphone's, what was sent, and the echo the canceller took out. For the level meter. */
    var lastMicRms = 0.0
        private set
    var lastSentRms = 0.0
        private set
    var lastEchoRms = 0.0
        private set

    /** The tail fade's gain now (1 = none). */
    val tailFade: Double get() = tailGain

    /** Whether the tail fade is holding the voice down at the moment (the microphone has been quiet for a while). */
    val fading: Boolean get() = tailGain < 0.999

    // What was sent out, chunk by chunk (RMS), for the last third of a second: the echo that the canceller takes out cannot be more than a share of
    // what was sent a quarter of a second before, so what it takes out of a loud shout after silence is not feedback.
    private val sentHistory = DoubleArray(SENT_HISTORY)
    private var sentIndex = 0

    private val reference = EchoReference(BROADCAST_CHUNK_SAMPLES)
    private val apmHighPass = Biquad(0.97949, -1.95898, 0.97949, -1.95337, 0.95532) // 80 Hz, as the audio processing module has it
    private val hum = Biquad.highPass(settings.highPassHz)
    private val whistle = Biquad.lowPass(settings.lowPassHz)
    private val loudWhistle = Biquad.lowPass(settings.loudLowPassHz)
    private var quietChunks = 0
    private var tailGain = 1.0

    // The loudest the microphone has been lately (a chunk's RMS): up by at most 1 % a chunk (a click does not move it, a voice does within a
    // second), down by 0.07 % a chunk (a half-life of about ten seconds), and never below the floor.
    private var speechPeak = settings.speechFloorRms

    // The quietest the microphone has been lately, for the music background: the RMS of each 100 ms frame of the last six seconds, and the lowest.
    private val frames = DoubleArray(BACKGROUND_FRAMES) { Double.MAX_VALUE }
    private var frameIndex = 0
    private var frameEnergy = 0.0
    private var frameChunks = 0
    private var backgroundRms = 0.0

    private var micEnergy = 0.0
    private var cleanedEnergy = 0.0
    private var sentEnergy = 0.0
    private var chunks = 0
    private var lowestTail = 1.0

    /**
     * Takes a chunk of the microphone (mono, 16-bit) and returns what to play, the same size. [silent] makes what comes out silence (a squeal is
     * being held back): everything else goes on as usual, and the silence is what is kept as played.
     */
    fun process(input: ShortArray, silent: Boolean = false): ShortArray {
        val n = input.size
        val raw = input.copyOf()
        val work = input.copyOf()
        val gainNow = min(gain, MAX_GAIN)
        var echoRms = 0.0

        // 1. Peak limit.
        var peak = 0
        for (v in work) peak = max(peak, abs(v.toInt()))
        if (peak > settings.inputPeak) {
            val factor = settings.inputPeak.toDouble() / peak
            for (i in 0 until n) work[i] = clip((work[i] * factor).roundToInt())
        }

        // 2. High-pass and echo cancellation.
        val cleaned: ShortArray
        if (echo != null) {
            val ref = if (reference.size < settings.referenceChunks) {
                // Before there is a reference of that age, the chunk itself stands in for it (two chunks at the start).
                work.copyOf().also { applyGain(it, gainNow) }
            } else {
                reference.back(settings.referenceChunks)
            }
            echo.addFarend(ref)
            for (i in 0 until n) work[i] = clip(apmHighPass.process(work[i].toDouble()).roundToInt())
            val before = energy(work)
            cleaned = echo.clean(work, settings.delayMs)
            echoRms = sqrt(max(0.0, before - energy(cleaned)) / max(1, n))
        } else {
            cleaned = work
        }

        lastCleaned = cleaned

        // 3. Dry mix.
        val rawRms = rms(raw)
        val scale = levelScale(rawRms)
        val out = cleaned.copyOf()
        if (rawRms >= settings.dryMixFromRms * scale) {
            val ratio = rms(cleaned) / rawRms
            if (ratio < settings.dryMixTargetRatio) {
                val dry = min(settings.dryMixMax, (settings.dryMixTargetRatio - ratio) * 0.5)
                for (i in 0 until n) out[i] = clip((raw[i] * dry + cleaned[i] * (1.0 - dry)).roundToInt())
            }
        }

        // 4. Gain.
        applyGain(out, gainNow)

        // 5. Hum and whistle filters, then the soft clip.
        val loud = rawRms >= settings.loudRms
        for (i in 0 until n) {
            var v = whistle.process(hum.process(out[i].toDouble()))
            if (loud) v = loudWhistle.process(v)
            var r = v.roundToInt()
            if (abs(r) > settings.clipFrom) r = (sign(r.toDouble()) * (settings.clipFrom + ((abs(r) - settings.clipFrom) * settings.clipSlope).roundToInt())).toInt()
            out[i] = clip(r)
        }

        // 6. Tail fade, from the level of what the microphone heard.
        followBackground(rawRms)
        if (musicBackground) {
            // Voice is what is clearly above the background; everything else is quiet, with no band in between where the gain is held (a steady
            // music would sit in it for good and never be faded).
            val bar = min(max(settings.tailSpeechRms * scale, BACKGROUND_SPEECH_TIMES * backgroundRms), BACKGROUND_MAX_SHARE_OF_VOICE * speechPeak)
            if (rawRms >= bar) {
                quietChunks = 0
                tailGain = 1.0
            } else {
                quietChunks++
                if (quietChunks >= settings.tailQuietChunks) tailGain = max(MUSIC_TAIL_MIN_GAIN, tailGain * settings.tailDecay)
            }
        } else if (rawRms >= settings.tailSpeechRms * scale) {
            quietChunks = 0
            tailGain = 1.0
        } else if (rawRms <= settings.tailQuietRms * scale) {
            quietChunks++
            if (quietChunks >= settings.tailQuietChunks) tailGain = max(settings.tailMinGain, tailGain * settings.tailDecay)
        } else {
            quietChunks = 0
        }
        if (tailGain < 0.999) for (i in 0 until n) out[i] = clip((out[i] * tailGain).roundToInt())

        if (silent) out.fill(0)
        reference.push(out)

        lastMicRms = rawRms
        lastSentRms = rms(out)
        // The echo is at most half of what was sent about a quarter of a second ago (the loop's delay is not known to the chunk: the loudest of
        // several chunks around it is taken).
        var earlier = 0.0
        for (back in ECHO_LOOK_BACK_FROM..ECHO_LOOK_BACK_TO) earlier = max(earlier, sentHistory[(sentIndex - back + SENT_HISTORY * 2) % SENT_HISTORY])
        lastEchoRms = min(echoRms, earlier * ECHO_SHARE_OF_SENT)
        sentHistory[sentIndex] = lastSentRms
        sentIndex = (sentIndex + 1) % SENT_HISTORY

        micEnergy += energy(raw)
        cleanedEnergy += energy(cleaned)
        sentEnergy += energy(out)
        chunks++
        lowestTail = min(lowestTail, tailGain)
        return out
    }

    /** The levels since the last call, and starts the count again. */
    fun takeStats(): Stats {
        val samples = max(1, chunks) * BROADCAST_CHUNK_SAMPLES.toDouble()
        val stats = Stats(toDb(micEnergy / samples), toDb(cleanedEnergy / samples), toDb(sentEnergy / samples), lowestTail, speechPeak)
        micEnergy = 0.0
        cleanedEnergy = 0.0
        sentEnergy = 0.0
        chunks = 0
        lowestTail = tailGain
        return stats
    }

    /** Keeps the quietest recent 100 ms frame ([backgroundRms]): what the music and the cabin are at when nobody is speaking. */
    private fun followBackground(chunkRms: Double) {
        frameEnergy += chunkRms * chunkRms
        if (++frameChunks < FRAME_CHUNKS) return
        frames[frameIndex] = sqrt(frameEnergy / frameChunks)
        frameIndex = (frameIndex + 1) % BACKGROUND_FRAMES
        frameEnergy = 0.0
        frameChunks = 0
        var lowest = Double.MAX_VALUE
        for (v in frames) lowest = min(lowest, v)
        backgroundRms = if (lowest == Double.MAX_VALUE) 0.0 else lowest
    }

    /** How the thresholds are scaled for this chunk (1 = as made for), after the chunk's level is taken into the speech level followed. */
    private fun levelScale(chunkRms: Double): Double {
        if (!settings.adaptiveLevels) return 1.0
        val decayed = speechPeak * PEAK_DECAY_PER_CHUNK
        speechPeak = max(settings.speechFloorRms, max(decayed, min(chunkRms, speechPeak * PEAK_RISE_PER_CHUNK)))
        return min(1.0, speechPeak / settings.referenceSpeechRms)
    }

    private fun applyGain(samples: ShortArray, factor: Float) {
        if (factor == 1.0f) return
        for (i in samples.indices) samples[i] = clip((samples[i] * factor).roundToInt())
    }

    private fun rms(samples: ShortArray): Double = sqrt(energy(samples) / max(1, samples.size))

    private fun energy(samples: ShortArray): Double {
        var sum = 0.0
        for (v in samples) sum += v.toDouble() * v
        return sum
    }

    private fun toDb(meanSquares: Double): Double = if (meanSquares < 1.0) -90.0 else 10.0 * log10(meanSquares / (32768.0 * 32768.0))

    private fun clip(v: Int): Short = v.coerceIn(-32768, 32767).toShort()

    /** A second-order section (transposed direct form II): y = b0 x + z1, with the coefficients divided by a0 already. */
    private class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            private const val Q = 0.707

            fun highPass(hz: Int): Biquad {
                val w = 2.0 * PI * hz / BROADCAST_SAMPLE_RATE
                val alpha = sin(w) / (2.0 * Q)
                val c = cos(w)
                val a0 = 1.0 + alpha
                return Biquad((1.0 + c) / 2.0 / a0, -(1.0 + c) / a0, (1.0 + c) / 2.0 / a0, -2.0 * c / a0, (1.0 - alpha) / a0)
            }

            fun lowPass(hz: Int): Biquad {
                val w = 2.0 * PI * hz / BROADCAST_SAMPLE_RATE
                val alpha = sin(w) / (2.0 * Q)
                val c = cos(w)
                val a0 = 1.0 + alpha
                return Biquad((1.0 - c) / 2.0 / a0, (1.0 - c) / a0, (1.0 - c) / 2.0 / a0, -2.0 * c / a0, (1.0 - alpha) / a0)
            }
        }
    }

    companion object {
        /** The most the gain can be (the apps this follows cap it there). */
        const val MAX_GAIN = 5.0f

        private const val PEAK_RISE_PER_CHUNK = 1.01
        private const val PEAK_DECAY_PER_CHUNK = 0.9993

        // The music background: a frame is 10 chunks, the lowest of the last 60 (six seconds) is the background, the voice has to be this many times it,
        // and the bar is never more than this share of the loudest voice lately; the fade goes down to this floor (-30 dB) while the music plays.
        private const val FRAME_CHUNKS = 10
        private const val BACKGROUND_FRAMES = 60
        private const val BACKGROUND_SPEECH_TIMES = 6.0
        private const val BACKGROUND_MAX_SHARE_OF_VOICE = 0.7
        private const val MUSIC_TAIL_MIN_GAIN = 0.03

        private const val SENT_HISTORY = 40
        private const val ECHO_LOOK_BACK_FROM = 18
        private const val ECHO_LOOK_BACK_TO = 30
        private const val ECHO_SHARE_OF_SENT = 0.5
    }
}

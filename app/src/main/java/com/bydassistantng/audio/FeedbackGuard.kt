package com.bydassistantng.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens to the cabin microphone during a live broadcast for the one thing that makes this kind of path dangerous: the microphone hearing the
 * exterior speaker, which builds up a squeal. A squeal is a loud, steady, tonal signal, which speech is not (speech is bursty and has sharp
 * peaks against its average), so a second of that is taken for feedback: [Result.feedback] says so once, and the sound is to be
 * held silent for a moment ([Result.muted]); the broadcast lowers its level and, if it comes back, stops.
 *
 * Time is counted in samples, so it can be tested with synthetic signals; one call takes one chunk of any size (the broadcast reads 10 ms).
 */
internal class FeedbackGuard(private val sampleRate: Int = BROADCAST_SAMPLE_RATE) {
    /** What was seen: the level that came in (after a high-pass at 150 Hz), in dB below full scale, whether the sound is to be held silent, and a squeal. */
    class Result(val inputDb: Double, val muted: Boolean, val feedback: Boolean)

    private val alpha = 1.0 / (1.0 + 2.0 * PI * HIGH_PASS_HZ / sampleRate)
    private var previousInput = 0.0
    private var previousOutput = 0.0
    private var mutedSamplesLeft = 0
    private val history = ArrayDeque<Pair<Double, Double>>() // (level in dB, crest factor) of the latest chunks

    fun observe(input: ShortArray): Result {
        if (input.isEmpty()) return Result(SILENCE_DB, mutedSamplesLeft > 0, false)
        var peak = 0.0
        var sumSquares = 0.0
        for (v in input) {
            val x = v / FULL_SCALE
            val y = alpha * (previousOutput + x - previousInput)
            previousInput = x
            previousOutput = y
            peak = max(peak, abs(y))
            sumSquares += y * y
        }
        val rms = sqrt(sumSquares / input.size)
        val levelDb = if (rms <= 1e-9) SILENCE_DB else 20.0 * log10(rms)

        // While the sound is held silent what the microphone hears says nothing about the speaker's output, so nothing is kept: a new squeal
        // needs fresh evidence.
        var feedback = false
        val windowChunks = max(MIN_FEEDBACK_CHUNKS, FEEDBACK_WINDOW_MS * sampleRate / 1000 / input.size)
        if (mutedSamplesLeft > 0) {
            history.clear()
        } else {
            history.addLast(levelDb to (if (rms > 0.0) peak / rms else 0.0))
            while (history.size > windowChunks) history.removeFirst()
        }
        if (mutedSamplesLeft <= 0 && isSqueal(windowChunks)) {
            feedback = true
            mutedSamplesLeft = FEEDBACK_MUTE_MS * sampleRate / 1000
            history.clear()
        }
        val muted = mutedSamplesLeft > 0
        if (mutedSamplesLeft > 0) mutedSamplesLeft = max(0, mutedSamplesLeft - input.size)
        return Result(levelDb, muted, feedback)
    }

    /**
     * Loud, steady and tonal for [FEEDBACK_WINDOW_MS] in a row. Speech is never all three at once (it comes in bursts, and a voiced sound has
     * sharp peaks against its average, a crest factor of 3 and more); a squeal is.
     */
    private fun isSqueal(windowChunks: Int): Boolean {
        if (history.size < windowChunks) return false
        val loud = history.all { (level, _) -> level >= FEEDBACK_MIN_DB }
        val tonal = history.all { (_, crest) -> crest in 0.5..FEEDBACK_MAX_CREST }
        val levels = history.map { it.first }
        val steady = (levels.max() - levels.min()) <= FEEDBACK_MAX_SPREAD_DB
        return loud && tonal && steady
    }

    companion object {
        private const val FULL_SCALE = 32768.0
        private const val SILENCE_DB = -90.0
        private const val HIGH_PASS_HZ = 150.0

        // A squeal: a whole second at or above this level (as many chunks as fit in it, at least a few), within this spread, with a crest factor
        // (peak over RMS) under this (a sine is 1.41; speech is 3 and up). A second, not half of one: a very loud shout is flattened by the
        // microphone's own gain control and the peak limit until it looks like a tone for a few tenths of a second, but it does not stay within a
        // few decibels for a second, and a real squeal does.
        internal const val FEEDBACK_WINDOW_MS = 1_000
        private const val MIN_FEEDBACK_CHUNKS = 3
        private const val FEEDBACK_MIN_DB = -26.0
        private const val FEEDBACK_MAX_SPREAD_DB = 3.5
        private const val FEEDBACK_MAX_CREST = 2.0

        // How long the sound is held silent after a squeal.
        internal const val FEEDBACK_MUTE_MS = 1_000
    }
}

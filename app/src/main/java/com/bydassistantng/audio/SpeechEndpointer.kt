package com.bydassistantng.audio

import kotlin.math.sqrt

/** Root-mean-square level of one chunk of little-endian 16-bit PCM. */
fun pcm16Rms(chunk: ByteArray): Int {
    val samples = chunk.size / 2
    if (samples == 0) return 0
    var sumSquares = 0.0
    for (i in 0 until samples) {
        val sample = ((chunk[2 * i + 1].toInt() shl 8) or (chunk[2 * i].toInt() and 0xFF)).toShort().toInt()
        sumSquares += sample.toDouble() * sample
    }
    return sqrt(sumSquares / samples).toInt()
}

/**
 * Notices when the user has finished speaking, from the mic level alone. The Live API's own
 * voice-activity detection ends the turn server-side but tells the client nothing until the reply
 * is already coming back — and the client wants to know *now*, to play the "got it" tone and to
 * stop the mic before the reply starts playing out of the speakers.
 *
 * Deliberately conservative, because it only ever makes things faster, never required: if the level
 * never settles (a noisy cabin) it simply never fires and the server's detection and the listening
 * cap still end the turn. It has no concept of words, only "was loud, then quiet for a while".
 *
 * Feed it one RMS value per chunk via [onChunk].
 */
// Speech is over once the level falls below this fraction of how loud it was (but never above the cap).
private const val RELATIVE_END_FRACTION = 0.2
private const val RELATIVE_END_CAP = 900.0

class SpeechEndpointer(
    private val chunkMs: Int = 50,
    /** Ignored at the start: the "listening" tone leaks into the mic, and the level hasn't settled. */
    private val warmupMs: Int = 400,
    /** Lowest level that can ever count as speech, however quiet the cabin is. Measured on the real
     * car: speech through this mic peaks around 230 over a room floor of about 8 (the capture gain is
     * low), so anything much above this never registered. */
    private val minThreshold: Int = 60,
    /** Speech has to be this many times louder than the quietest level seen so far. */
    private val noiseMultiplier: Double = 3.0,
    /** How long the level has to stay up before it counts as speech rather than a bump. */
    private val speechStartMs: Int = 150,
    /** How long it has to stay down after speech before the user counts as finished. */
    private val silenceEndMs: Int = 800,
) {
    var speechDetected = false
        private set
    var peak = 0
        private set

    private var elapsedMs = 0
    private var speechLevel = 0.0 // peak-hold of the loud chunks: roughly how loud this user is speaking
    private var noiseFloor = Double.MAX_VALUE
    private var loudMs = 0
    private var quietMs = 0
    private var ended = false

    val threshold: Int get() = maxOf((noiseFloor * noiseMultiplier).toInt().takeIf { noiseFloor != Double.MAX_VALUE } ?: 0, minThreshold)

    /** @return true exactly once: the first time the user has been speaking and then gone quiet for
     * [silenceEndMs]. */
    fun onChunk(rms: Int): Boolean {
        if (ended) return false
        elapsedMs += chunkMs
        peak = maxOf(peak, rms)

        if (elapsedMs <= warmupMs) return false

        // The quietest the room has been is the best available idea of "silence" — taking the minimum
        // rather than an average keeps speech that starts right after the tone from raising the bar.
        // (A chunk's RMS is already a 50ms average, and a bump shorter than [speechStartMs] is
        // ignored below, so no further smoothing is needed.)
        if (!speechDetected) noiseFloor = minOf(noiseFloor, rms.toDouble())

        // Once speech has started, "quiet" is judged against how loud the speech was as well as against the
        // floor learnt at the start: the cabin can get noisier mid-turn (the A/C going to maximum took the
        // blower above the learnt floor, so the pause never registered and only the server ended the turn
        // ~11s later). Capped, so very loud speech can't make a soft sentence-end vanish.
        val loud = if (speechDetected) rms >= maxOf(threshold.toDouble(), minOf(speechLevel * RELATIVE_END_FRACTION, RELATIVE_END_CAP)) else rms >= threshold
        if (loud) {
            if (rms >= threshold) speechLevel = maxOf(rms.toDouble(), speechLevel * 0.995)
            loudMs += chunkMs
            quietMs = 0
            if (loudMs >= speechStartMs) speechDetected = true
        } else {
            loudMs = 0
            if (speechDetected) {
                quietMs += chunkMs
                if (quietMs >= silenceEndMs) {
                    ended = true
                    return true
                }
            }
        }
        return false
    }

    /** For when the user is already mid-sentence (they just talked over the assistant): skips the
     * warm-up, takes the cabin's [noiseFloor] from elsewhere, and counts speech as started — so the end of
     * what they say is found like any other. */
    fun beginMidSpeech(noiseFloor: Double) {
        elapsedMs = warmupMs + chunkMs
        this.noiseFloor = noiseFloor.coerceAtLeast(1.0)
        speechDetected = true
        speechLevel = 2_000.0 // they were just loud enough to be noticed over the assistant
        loudMs = speechStartMs
        quietMs = 0
    }

    fun summary(): String =
        "endpointer: floor=${if (noiseFloor == Double.MAX_VALUE) -1 else noiseFloor.toInt()} threshold=$threshold peak=$peak speech=$speechDetected"
}

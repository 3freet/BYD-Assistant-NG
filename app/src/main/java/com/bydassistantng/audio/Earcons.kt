package com.bydassistantng.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

private const val TAG = "Earcons"
private const val SAMPLE_RATE = 44_100

/** What the assistant is telling the user without words. Each has its own shape so they can be told
 * apart without looking: up = "go ahead", a quiet pair of ticks = "got it, working", down = "all
 * done", low = "that failed". */
enum class Earcon { LISTENING, THINKING, DONE, ERROR, OFFLINE }

/**
 * Short synthesized tones, played on the same audio path as the assistant's voice. Each play gets a
 * brand-new [AudioTrack] that is released afterwards: the previous approach (one reused
 * `ToneGenerator`) played the first tone and then went silent on the head unit.
 */
@Singleton
class Earcons @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tones: Map<Earcon, ShortArray> by lazy {
        mapOf(
            // Bright and rising, a little longer on the last note: "I'm listening".
            Earcon.LISTENING to sequence(Note(659.25, 110), Note(987.77, 190)),
            // Two soft, quick ticks at one pitch: "heard you, thinking".
            Earcon.THINKING to sequence(Note(783.99, 70, amplitude = 0.25), Note(0.0, 55), Note(783.99, 70, amplitude = 0.25)),
            // The listening tone reversed and settling lower: "finished".
            Earcon.DONE to sequence(Note(987.77, 110), Note(659.25, 240)),
            // Low and dull, so it can't be mistaken for the others.
            Earcon.ERROR to sequence(Note(293.66, 150, amplitude = 0.4), Note(220.0, 260, amplitude = 0.4)),
            // Two short blips then a long low note: "can't reach the internet" — not the plain error tone.
            Earcon.OFFLINE to sequence(Note(392.0, 90, amplitude = 0.4), Note(0.0, 60), Note(392.0, 90, amplitude = 0.4), Note(0.0, 60), Note(261.63, 320, amplitude = 0.4)),
        )
    }

    /** Fire and forget: returns immediately, the tone plays on a background thread. */
    fun play(earcon: Earcon) {
        scope.launch {
            runCatching {
                val pcm = tones.getValue(earcon)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // Same usage as the spoken replies, which are known to be audible on this unit.
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                try {
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    delay(pcm.size * 1000L / SAMPLE_RATE + 80)
                } finally {
                    track.release()
                }
            }.onFailure { AppLogger.logError(TAG, "Could not play $earcon", it) }
        }
    }

    private class Note(val frequencyHz: Double, val durationMs: Int, val amplitude: Double = 0.45)

    /** Strings notes together. A frequency of 0 is a rest. */
    private fun sequence(vararg notes: Note): ShortArray {
        val out = ArrayList<Short>()
        for (note in notes) {
            val count = SAMPLE_RATE * note.durationMs / 1000
            // Decay time constant: the note rings out over roughly its whole length.
            val tau = note.durationMs / 1000.0 / 3.0
            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                if (note.frequencyHz == 0.0) {
                    out += 0
                    continue
                }
                // A touch of 2nd/3rd harmonic makes it a soft bell rather than a bare sine.
                val wave = sin(2 * PI * note.frequencyHz * t) +
                    0.30 * sin(2 * PI * note.frequencyHz * 2 * t) +
                    0.08 * sin(2 * PI * note.frequencyHz * 3 * t)
                val attack = (t / 0.006).coerceAtMost(1.0) // 6ms fade-in, so there is no click
                val release = ((count - i).toDouble() / SAMPLE_RATE / 0.012).coerceAtMost(1.0) // and no click at the end
                val sample = wave / 1.38 * note.amplitude * attack * release * exp(-t / tau)
                out += (sample * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out.toShortArray()
    }
}

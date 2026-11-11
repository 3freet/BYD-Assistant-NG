package com.bydassistantng.debug

import android.content.Context
import com.bydassistantng.audio.BROADCAST_SAMPLE_RATE
import com.bydassistantng.audio.BroadcastCapture
import com.bydassistantng.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import kotlin.math.log10
import kotlin.math.sqrt

private const val TAG = "MicListener"

/**
 * DEBUG BUILDS ONLY. Listens to the cabin microphone the way the live broadcast does, for a few seconds, and plays nothing at all: it logs what the
 * unit gave (the source, how many channels) and the level of every second, and keeps the sound in `files/listen_<time>.pcm` (16 kHz mono 16-bit). It is for
 * measuring what a broadcast hears while another app does the broadcasting, and for checking the microphone without a sound anywhere.
 */
internal object MicListener {
    fun run(context: Context, seconds: Int, stereo: Boolean, agcOff: Boolean) {
        val capture = BroadcastCapture(agcOff = agcOff, stereo = stereo)
        val file = File(context.filesDir, "listen_${System.currentTimeMillis()}.pcm")
        val out = FileOutputStream(file)
        var chunks = 0
        var sum = 0.0
        var peak = 0
        val started = capture.start { samples, _ ->
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                bytes[2 * i] = (samples[i].toInt() and 0xFF).toByte()
                bytes[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
                sum += samples[i].toDouble() * samples[i]
                peak = maxOf(peak, kotlin.math.abs(samples[i].toInt()))
            }
            runCatching { out.write(bytes) }
            chunks++
            if (chunks % 100 == 0) {
                val rms = sqrt(sum / (100.0 * samples.size))
                AppLogger.log(TAG, "Second ${chunks / 100}: ${"%.1f".format(if (rms < 1.0) -90.0 else 20 * log10(rms / 32768.0))} dB RMS, peak $peak")
                sum = 0.0
                peak = 0
            }
        }
        if (!started) {
            AppLogger.log(TAG, "The microphone would not start")
            out.close()
            return
        }
        AppLogger.log(TAG, "Listening for $seconds s: ${capture.sourceName}, ${capture.channels} channel(s), ${BROADCAST_SAMPLE_RATE} Hz, unit gain control ${if (agcOff) "off" else "on"} -> ${file.name}")
        Thread.sleep(seconds * 1000L + 300)
        capture.stop()
        out.close()
        AppLogger.log(TAG, "Done: ${file.name}, ${file.length() / 2 / BROADCAST_SAMPLE_RATE} s")
    }
}

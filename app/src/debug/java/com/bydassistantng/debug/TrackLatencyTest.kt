package com.bydassistantng.debug

import android.annotation.SuppressLint
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import com.bydassistantng.audio.BROADCAST_CHUNK_SAMPLES
import com.bydassistantng.audio.BROADCAST_SAMPLE_RATE
import com.bydassistantng.audio.ExteriorSpeaker
import com.bydassistantng.audio.MusicAudio
import com.bydassistantng.audio.NaviAudio
import com.bydassistantng.util.AppLogger

/**
 * How long the broadcast's track keeps sound waiting, measured with silence: a track made as the broadcast makes it (the music stream, 16 kHz mono,
 * 160 ms of buffer) is fed 10 ms chunks of zeros at the pace of a microphone, and the platform's own timestamps say how much of what was written had
 * not yet been played. **No route is opened and nothing audible is written**, so nothing is heard anywhere. Debug builds only.
 */
internal object TrackLatencyTest {
    private const val TAG = "TrackLatencyTest"

    // A tool for the car (Android 13), run by hand: the calls that need a newer Android than the app's lowest are fine there.
    @SuppressLint("NewApi")
    fun run(speaker: ExteriorSpeaker, startThresholdMs: Int, seconds: Int, navigation: Boolean = false, boostMb: Int = 0) {
        val track = speaker.newLiveTrack(
            BROADCAST_SAMPLE_RATE,
            if (navigation) NaviAudio.attributes() else MusicAudio.attributes(),
            lowLatency = false,
            bufferFactor = 1,
            startThresholdMs = startThresholdMs,
            minBufferBytes = 5_120,
        )
        // The platform's loudness boost the navigation route's broadcast uses, attached the same way (the audio is zeros, so nothing is heard).
        val boost = if (boostMb > 0) {
            runCatching {
                android.media.audiofx.LoudnessEnhancer(track.audioSessionId).apply {
                    setTargetGain(boostMb)
                    enabled = true
                }
            }.onFailure { AppLogger.log(TAG, "The loudness boost would not attach: $it") }.getOrNull()
        } else {
            null
        }
        val chunk = ShortArray(BROADCAST_CHUNK_SAMPLES)
        val timestamp = AudioTimestamp()
        val threshold = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) track.startThresholdInFrames * 1000 / BROADCAST_SAMPLE_RATE else -1
        val hiddenLatency = runCatching { AudioTrack::class.java.getMethod("getLatency").invoke(track) as Int }.getOrNull()
        val queued = ArrayList<Long>()
        var firstPresentedAfterMs = -1L
        var written = 0L
        val began = SystemClock.elapsedRealtime()
        val beganNanos = System.nanoTime()
        track.play()
        val chunks = seconds * 100
        for (i in 0 until chunks) {
            // The pace of a microphone: one chunk every 10 ms.
            val due = beganNanos + i * 10_000_000L
            val wait = (due - System.nanoTime()) / 1_000_000L
            if (wait > 0) Thread.sleep(wait)
            track.write(chunk, 0, chunk.size)
            written += chunk.size
            if (track.getTimestamp(timestamp)) {
                if (firstPresentedAfterMs < 0 && timestamp.framePosition > 0) firstPresentedAfterMs = SystemClock.elapsedRealtime() - began
                if (i % 50 == 49) {
                    val played = timestamp.framePosition + (System.nanoTime() - timestamp.nanoTime) * BROADCAST_SAMPLE_RATE / 1_000_000_000L
                    queued += (written - played) * 1000 / BROADCAST_SAMPLE_RATE
                }
            }
        }
        val underruns = track.underrunCount
        val mode = track.performanceMode
        val bufferMs = track.bufferSizeInFrames * 1000 / BROADCAST_SAMPLE_RATE
        runCatching { track.stop() }
        runCatching { boost?.release() }
        track.release()
        AppLogger.log(
            TAG,
            "${if (navigation) "NAVIGATION stream, boost $boostMb mB (${if (boost != null) "attached" else "none"}); " else ""}start threshold asked ${startThresholdMs} ms, the track says $threshold ms; buffer $bufferMs ms, performance mode $mode, " +
                "latency the platform reports $hiddenLatency ms; first frame played ${firstPresentedAfterMs} ms after the first write; " +
                "written and not yet played every half second: ${queued.joinToString(", ")} ms; underruns $underruns",
        )
    }
}

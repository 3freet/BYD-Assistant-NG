package com.bydassistantng.debug

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.media.audiofx.LoudnessEnhancer
import com.bydassistantng.audio.BROADCAST_SAMPLE_RATE
import com.bydassistantng.audio.BroadcastCapture
import com.bydassistantng.audio.ExteriorSpeaker
import com.bydassistantng.audio.ExteriorVolume
import com.bydassistantng.audio.MusicAudio
import com.bydassistantng.audio.NaviAudio
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "LiveBroadcast"

/**
 * DEBUG BUILDS ONLY. Measures how long a sound written to the exterior speaker takes to reach the cabin microphone, for several ways
 * of making the track, which is the delay a live broadcast has between being said and being heard outside (the loop is the same two
 * stretches, the track's output and the microphone's input) and the delay of the echo that comes back. Three short bursts of sound
 * come out of the car for each configuration, and the cabin must be quiet meanwhile.
 *
 * Two microphones listen at once: a raw one (no processing, so the burst is heard as it is) and the voice-processed one the broadcast
 * uses. How much quieter the burst is in the second is how well the car's own echo cancellation takes the exterior speaker's sound
 * out. The result is logged as `LiveBroadcast: LAB ...` lines.
 */
internal class BroadcastLab(private val context: Context, private val speaker: ExteriorSpeaker) {
    /** One way of playing: the route (the navigation stream's or the media route's), how the track is made, and how the processed microphone is set. */
    class Config(
        val name: String,
        val media: Boolean,
        val attributes: AudioAttributes,
        val lowLatency: Boolean,
        val bufferFactor: Int,
        val agcOff: Boolean = true,
        val noiseSuppressionOff: Boolean = false,
        val echoCancellationOff: Boolean = false,
    )

    private class Piece(val capturedAtNanos: Long, val samples: ShortArray)

    /** The routes compared: the navigation stream's (as the assistant uses it) and the media route (the music stream, as the exterior sound apps use it). */
    val routeConfigs: List<Config> = listOf(
        Config("navi-route", media = false, NaviAudio.attributes(), lowLatency = true, bufferFactor = 1),
        Config("media-route", media = true, MusicAudio.attributes(), lowLatency = true, bufferFactor = 1),
        Config("media-route-double-buffer", media = true, MusicAudio.attributes(), lowLatency = true, bufferFactor = 2),
    )

    /** What the unit's own processing does to the exterior speaker's sound reaching the microphone, on the media route, one effect at a time. */
    val effectConfigs: List<Config> = listOf(
        Config("effects: all on", media = true, MusicAudio.attributes(), lowLatency = true, bufferFactor = 1, agcOff = false),
        Config("effects: gain control off", media = true, MusicAudio.attributes(), lowLatency = true, bufferFactor = 1),
        Config("effects: gain control + noise suppression off", media = true, MusicAudio.attributes(), lowLatency = true, bufferFactor = 1, noiseSuppressionOff = true),
        Config("effects: gain control + echo cancellation off", media = true, MusicAudio.attributes(), lowLatency = true, bufferFactor = 1, echoCancellationOff = true),
        Config("effects: all off", media = true, MusicAudio.attributes(), lowLatency = true, bufferFactor = 1, noiseSuppressionOff = true, echoCancellationOff = true),
    )

    suspend fun run(volume: ExteriorVolume, configs: List<Config>) {
        AppLogger.log(TAG, "LAB start at ${volume.word}: ${configs.size} configurations, about ${configs.size * 7} s; keep the cabin quiet")
        for (config in configs) {
            runCatching { one(config, volume) }.onFailure { AppLogger.logError(TAG, "LAB ${config.name} failed", it) }
            delay(800)
        }
        AppLogger.log(TAG, "LAB done")
    }

    /**
     * Plays the speech clip in `files/lab_speech.pcm` (16 kHz mono 16-bit) through the same route and track as a live broadcast, one chunk out for every
     * chunk the processed microphone delivers, and writes what was played (`lab_far.pcm`) and what the microphone heard (`lab_mic.pcm`), chunk for chunk,
     * to the app's files, for trying an echo canceller on the car's real echo path offline.
     */
    suspend fun speech(volume: ExteriorVolume) {
        val clipFile = File(context.filesDir, "lab_speech.pcm")
        if (!clipFile.exists()) return AppLogger.log(TAG, "LAB speech: files/lab_speech.pcm is missing")
        val bytes = clipFile.readBytes()
        val clip = ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
        val chunk = BROADCAST_SAMPLE_RATE / 100
        val total = clip.size + BROADCAST_SAMPLE_RATE // a second more, for the tail of the echo
        AppLogger.log(TAG, "LAB speech start at ${volume.word}: ${clip.size / BROADCAST_SAMPLE_RATE} s of speech out of the car; keep the cabin quiet")
        if (!speaker.openMedia()) return AppLogger.log(TAG, "LAB speech: the exterior speaker would not switch")
        val track = speaker.newLiveTrack(BROADCAST_SAMPLE_RATE, MusicAudio.attributes(), lowLatency = true, bufferFactor = 2)
        val boost = runCatching { LoudnessEnhancer(track.audioSessionId).apply { setTargetGain(volume.boostMb); enabled = true } }.getOrNull()
        track.play()
        val far = ShortArray(total)
        val mic = ShortArray(total)
        var written = 0
        val capture = BroadcastCapture()
        val started = capture.start { samples, _ ->
            if (written + chunk <= total) {
                System.arraycopy(samples, 0, mic, written, minOf(samples.size, chunk))
                val out = ShortArray(chunk)
                for (i in 0 until chunk) if (written + i < clip.size) out[i] = clip[written + i]
                System.arraycopy(out, 0, far, written, chunk)
                track.write(out, 0, chunk)
                written += chunk
            }
        }
        if (!started) AppLogger.log(TAG, "LAB speech: the microphone would not start")
        delay(total * 1000L / BROADCAST_SAMPLE_RATE + 500)
        capture.stop()
        val underruns = track.underrunCount
        runCatching { track.stop() }
        delay(400)
        runCatching { boost?.release() }
        runCatching { track.release() }
        delay(500)
        speaker.close()
        fun save(name: String, data: ShortArray, count: Int) {
            val out = ByteArray(count * 2)
            for (i in 0 until count) {
                out[2 * i] = (data[i].toInt() and 0xFF).toByte()
                out[2 * i + 1] = (data[i].toInt() shr 8).toByte()
            }
            File(context.filesDir, name).writeBytes(out)
        }
        save("lab_far.pcm", far, written)
        save("lab_mic.pcm", mic, written)
        AppLogger.log(TAG, "LAB speech done: ${written / chunk} chunks written to files/lab_far.pcm and files/lab_mic.pcm, underruns $underruns")
    }

    private suspend fun one(config: Config, volume: ExteriorVolume) {
        val opened = if (config.media) speaker.openMedia() else speaker.open()
        if (!opened) {
            AppLogger.log(TAG, "LAB ${config.name}: the exterior speaker would not switch (media route: ${config.media})")
            return
        }
        val track = speaker.newLiveTrack(BROADCAST_SAMPLE_RATE, config.attributes, config.lowLatency, config.bufferFactor)
        val boost = runCatching { LoudnessEnhancer(track.audioSessionId).apply { setTargetGain(volume.boostMb); enabled = true } }.getOrNull()
        track.play()

        val raw = BroadcastCapture(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION"))
        val processed = BroadcastCapture(
            listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION"),
            agcOff = config.agcOff,
            noiseSuppressionOff = config.noiseSuppressionOff,
            echoCancellationOff = config.echoCancellationOff,
        )
        val rawPieces = ArrayList<Piece>()
        val processedPieces = ArrayList<Piece>()
        val burst = burst()
        val clickAt = LongArray(3)
        var clicksStarted = 0
        var burstOffset = -1
        val startNanos = System.nanoTime()
        val chunk = BROADCAST_SAMPLE_RATE / 100

        // The writer is driven by the raw capture's chunks, as the broadcast's is by the microphone's: one chunk in, one chunk out.
        val rawStarted = raw.start { samples, capturedAt ->
            synchronized(rawPieces) { rawPieces += Piece(capturedAt, samples) }
            val sinceStartMs = (System.nanoTime() - startNanos) / 1_000_000L
            val nextClickDue = 900L + clicksStarted * 1_500L
            if (burstOffset < 0 && clicksStarted < clickAt.size && sinceStartMs >= nextClickDue) {
                burstOffset = 0
                clickAt[clicksStarted] = System.nanoTime()
                clicksStarted++
            }
            val out = ShortArray(chunk)
            if (burstOffset >= 0) {
                for (i in 0 until chunk) if (burstOffset + i < burst.size) out[i] = burst[burstOffset + i]
                burstOffset += chunk
                if (burstOffset >= burst.size) burstOffset = -1
            }
            track.write(out, 0, out.size)
        }
        val processedStarted = processed.start { samples, capturedAt -> synchronized(processedPieces) { processedPieces += Piece(capturedAt, samples) } }
        if (!rawStarted) AppLogger.log(TAG, "LAB ${config.name}: the raw microphone would not start")
        delay(900L + clickAt.size * 1_500L + 400L)
        raw.stop()
        processed.stop()
        val underruns = track.underrunCount
        val bufferMs = track.bufferSizeInFrames * 1000 / BROADCAST_SAMPLE_RATE
        runCatching { track.stop() }
        delay(400)
        runCatching { boost?.release() }
        runCatching { track.release() }
        delay(500)
        speaker.close()

        val rawHits = analyse(rawPieces, clickAt)
        val processedHits = analyse(processedPieces, clickAt)
        AppLogger.log(
            TAG,
            "LAB ${config.name}: buffer $bufferMs ms, underruns $underruns, raw microphone: ${describe(rawHits)}; voice-processed microphone: ${describe(processedHits)}" +
                echoNote(rawHits, processedHits),
        )
    }

    /** 80 ms of a voiced sound (harmonics of 200 Hz), the kind the microphone's processing treats as speech and leaves alone. */
    private fun burst(): ShortArray {
        val n = BROADCAST_SAMPLE_RATE * 80 / 1000
        return ShortArray(n) { i ->
            var v = 0.0
            for (h in 1..12) v += sin(2 * PI * 200.0 * h * i / BROADCAST_SAMPLE_RATE) / h
            val envelope = minOf(1.0, i / (BROADCAST_SAMPLE_RATE * 0.005)) * minOf(1.0, (n - i) / (BROADCAST_SAMPLE_RATE * 0.005))
            (v * envelope * 0.35 * 32767).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private class Hit(val arrivalMs: Double?, val peakDb: Double)

    private fun levelDb(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) sum += (s / 32768.0) * (s / 32768.0)
        val rms = sqrt(sum / maxOf(1, samples.size))
        return if (rms < 1e-9) -90.0 else 20 * log10(rms)
    }

    /** When each burst first shows up in [pieces] (the delay from [clickAt], in ms, null when it does not) and how loud it gets. */
    private fun analyse(pieces: List<Piece>, clickAt: LongArray): List<Hit> {
        val all = synchronized(pieces) { pieces.toList() }
        if (all.isEmpty() || clickAt[0] == 0L) return clickAt.map { Hit(null, -90.0) }
        val before = all.filter { it.capturedAtNanos < clickAt[0] - 100_000_000L }.map { levelDb(it.samples) }.sorted()
        val baseline = if (before.isEmpty()) -70.0 else before[before.size / 2]
        val threshold = maxOf(baseline + 12.0, -55.0)
        return clickAt.map { t0 ->
            if (t0 == 0L) return@map Hit(null, -90.0)
            val window = all.filter { it.capturedAtNanos > t0 && it.capturedAtNanos < t0 + 1_200_000_000L }
            val levels = window.map { levelDb(it.samples) }
            val index = levels.indices.firstOrNull { levels[it] >= threshold && (it + 1 >= levels.size || levels[it + 1] >= threshold - 8.0) }
            Hit(index?.let { (window[it].capturedAtNanos - 5_000_000L - t0) / 1e6 }, levels.maxOrNull() ?: -90.0)
        }
    }

    private fun describe(hits: List<Hit>): String {
        val delays = hits.mapNotNull { it.arrivalMs }.sorted()
        val median = if (delays.isEmpty()) "not heard" else "median ${"%.0f".format(delays[delays.size / 2])} ms"
        return "$median, delays ${hits.joinToString { it.arrivalMs?.let { ms -> "%.0f".format(ms) } ?: "-" }} ms, peak ${hits.joinToString { "%.0f".format(it.peakDb) }} dB"
    }

    private fun echoNote(raw: List<Hit>, processed: List<Hit>): String {
        val r = raw.map { it.peakDb }.average()
        val p = processed.map { it.peakDb }.average()
        return " => the voice processing takes ${"%.0f".format(r - p)} dB off what the raw microphone hears (negative: it adds)"
    }
}

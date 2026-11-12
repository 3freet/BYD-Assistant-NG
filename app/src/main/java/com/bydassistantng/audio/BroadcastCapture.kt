package com.bydassistantng.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import com.bydassistantng.util.AppLogger

private const val TAG = "BroadcastCapture"

/** The sample rate of the live broadcast's capture and playback. */
internal const val BROADCAST_SAMPLE_RATE = 16_000

/** How much sound is read at a time: 10 ms, so that nothing waits long for a chunk to fill. */
internal const val BROADCAST_CHUNK_SAMPLES = BROADCAST_SAMPLE_RATE / 100

// The voice-processed source first (the car puts its echo cancellation, noise suppression and gain control on it), as in a conversation.
internal val BROADCAST_SOURCES = listOf(
    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
    MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
)

/**
 * The cabin microphone for the live broadcast: 16 kHz in small chunks on a thread of its own at audio priority, with as little buffering as the
 * platform allows. A broadcast is heard outside as it is said, so every stretch of buffering on the way is a delay the speaker notices (and, since
 * the exterior speaker can be heard in the cabin, an echo of their own voice).
 *
 * Two channels are asked for first, as the exterior sound apps do, and the two are averaged into one; a unit that gives only one gives one. The
 * unit's own processing (gain control, noise suppression) is left as it is unless asked otherwise: the chain behind this ([TalkChain]) is
 * made for the level the voice-processed source has with it on.
 */
internal class BroadcastCapture(
    private val sources: List<Pair<Int, String>> = BROADCAST_SOURCES,
    /** Whether the unit's automatic gain control, which the voice-processed source has on it, is switched off for this capture. */
    private val agcOff: Boolean = false,
    /** Also switch off the unit's noise suppression / echo cancellation on this capture (for measuring what each does). */
    private val noiseSuppressionOff: Boolean = false,
    private val echoCancellationOff: Boolean = false,
    /** Ask for two channels first (and average them); false = one channel only. */
    private val stereo: Boolean = true,
) {
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null

    /** The record's audio session, for effects; 0 when nothing is recording. */
    val sessionId: Int get() = record?.audioSessionId ?: 0

    /**
     * Called, from the capture thread, when the capture ends by itself while it was meant to go on: the record failed (an error from `read`, for
     * example when the audio server restarted) or the thread's work threw. Not called for a [stop]. Set before [start].
     */
    @Volatile var onLost: (() -> Unit)? = null

    @Volatile var sourceName = ""
        private set

    /** How many channels the unit gave (1 or 2; two are averaged). */
    @Volatile var channels = 1
        private set

    /** Whether the voice-processed source was given (the unit's echo cancellation and noise suppression are on it). */
    @Volatile var voiceProcessed = false
        private set

    /**
     * Starts reading. [onChunk] gets each chunk (about 10 ms of 16-bit mono samples) and the time, in the `System.nanoTime()` clock, at
     * which its last sample was captured as far as the record's own timestamps say (the time it was read when they are not given). It
     * runs on the capture thread, so it must not block for long. False when no source would start.
     */
    fun start(chunkSamples: Int = BROADCAST_CHUNK_SAMPLES, onChunk: (ShortArray, Long) -> Unit): Boolean {
        if (record != null) return false
        val channelConfigs = if (stereo) listOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO) else listOf(AudioFormat.CHANNEL_IN_MONO)
        var started: Started? = null
        search@ for ((source, name) in sources) {
            for (config in channelConfigs) {
                val count = if (config == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                val minBuffer = AudioRecord.getMinBufferSize(BROADCAST_SAMPLE_RATE, config, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) continue
                // Not smaller than a few chunks: a read that comes a little late must not lose sound.
                val bufferBytes = maxOf(minBuffer, chunkSamples * 2 * count * 4)
                val candidate = try {
                    AudioRecord(source, BROADCAST_SAMPLE_RATE, config, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                } catch (e: SecurityException) {
                    // The permission was revoked between the check at the start of the broadcast and now.
                    AppLogger.logError(TAG, "Microphone permission is not granted ($name)", e)
                    null
                } catch (e: Exception) {
                    AppLogger.logError(TAG, "Could not make an AudioRecord ($name, $count channels)", e)
                    null
                }
                when {
                    candidate == null -> Unit
                    candidate.state != AudioRecord.STATE_INITIALIZED -> {
                        AppLogger.log(TAG, "AudioRecord ($name, $count channels) did not initialize (state ${candidate.state})")
                        candidate.release()
                    }
                    else -> {
                        started = Started(candidate, name, source == MediaRecorder.AudioSource.VOICE_COMMUNICATION, candidate.channelCount.coerceIn(1, 2), bufferBytes)
                        break@search
                    }
                }
            }
        }
        val (audioRecord, name, processed, count, bufferBytes) = started ?: return false
        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            AppLogger.logError(TAG, "startRecording failed", e)
            audioRecord.release()
            return false
        }
        record = audioRecord
        sourceName = name
        voiceProcessed = processed
        channels = count
        if (agcOff && AutomaticGainControl.isAvailable()) {
            agc = runCatching { AutomaticGainControl.create(audioRecord.audioSessionId)?.also { it.enabled = false } }.getOrNull()
            AppLogger.log(TAG, "Automatic gain control: ${if (agc != null) "switched off for this capture" else "not reachable"}")
        }
        if (noiseSuppressionOff && NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(audioRecord.audioSessionId)?.also { it.enabled = false } }.getOrNull()
            AppLogger.log(TAG, "Noise suppression: ${if (ns != null) "switched off for this capture" else "not reachable"}")
        }
        if (echoCancellationOff && AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(audioRecord.audioSessionId)?.also { it.enabled = false } }.getOrNull()
            AppLogger.log(TAG, "Echo cancellation: ${if (aec != null) "switched off for this capture" else "not reachable"}")
        }
        AppLogger.log(TAG, "Capture started: $name, $count channel${if (count == 1) "" else "s"}, ${chunkSamples * 1000 / BROADCAST_SAMPLE_RATE} ms chunks, buffer $bufferBytes bytes")

        val reader = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val buffer = ShortArray(chunkSamples * count)
            val timestamp = AudioTimestamp()
            var framesRead = 0L
            // The record's own timestamp (a capture position and the time it was reached) is taken now and then and projected forward.
            var anchorFrames = -1L
            var anchorNanos = 0L
            var sinceAnchor = 0
            var lost = false
            // Nothing that goes wrong in here may end the process: a plain thread that throws takes the whole app with it, and the exterior route
            // with it left on. It ends the capture and says so, and the broadcast is ended the proper way.
            try {
                while (record === audioRecord) {
                    val n = audioRecord.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        AppLogger.logError(TAG, "AudioRecord.read() returned $n")
                        lost = true
                        break
                    }
                    if (n == 0) continue
                    val frames = n / count
                    if (frames == 0) continue
                    framesRead += frames
                    val now = System.nanoTime()
                    if (anchorFrames < 0 || ++sinceAnchor >= 50) {
                        sinceAnchor = 0
                        if (audioRecord.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                            anchorFrames = timestamp.framePosition
                            anchorNanos = timestamp.nanoTime
                        }
                    }
                    val capturedAt = if (anchorFrames >= 0) anchorNanos + (framesRead - anchorFrames) * 1_000_000_000L / BROADCAST_SAMPLE_RATE else now
                    val mono = if (count == 1) buffer.copyOf(frames) else ShortArray(frames) { i -> ((buffer[2 * i] + buffer[2 * i + 1]) shr 1).toShort() }
                    onChunk(mono, capturedAt)
                }
            } catch (e: Throwable) {
                AppLogger.logError(TAG, "The capture thread failed", e)
                lost = true
            }
            if (lost && record === audioRecord) onLost?.invoke()
        }, "BroadcastCapture")
        thread = reader
        reader.start()
        return true
    }

    private data class Started(val record: AudioRecord, val name: String, val processed: Boolean, val channels: Int, val bufferBytes: Int)

    fun stop() {
        val audioRecord = record ?: return
        record = null
        runCatching { audioRecord.stop() }
        // The reader notices that it is no longer the current record, or that read() returned an error, and ends; the record is only
        // released once it has, since releasing it under a read is what crashes.
        val reader = thread
        thread = null
        runCatching { reader?.join(500) }
        runCatching { agc?.release() }
        runCatching { ns?.release() }
        runCatching { aec?.release() }
        agc = null
        ns = null
        aec = null
        runCatching { audioRecord.release() }
        AppLogger.log(TAG, "Capture stopped")
    }
}

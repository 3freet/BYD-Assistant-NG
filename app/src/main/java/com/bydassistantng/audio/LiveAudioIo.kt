package com.bydassistantng.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "LiveAudioIo"

// Fixed by the Live API protocol itself — see GeminiLiveClient's INPUT_AUDIO_MIME and the
// documented output format ("raw 16-bit PCM audio, 24kHz, little-endian"), not a tunable choice.
private const val INPUT_SAMPLE_RATE = 16_000
private const val OUTPUT_SAMPLE_RATE = 24_000
private const val PLAYED_HISTORY_MS = 3_000L
private const val CHUNK_SIZE_BYTES = 1_600 // 50ms of 16kHz mono 16-bit PCM

private val SOURCES = listOf(
    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
    MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
)

/** Continuous raw-PCM microphone capture for the Live API's streamed audio input. Not annotated
 * `@Singleton`: it owns one [AudioRecord]'s lifecycle across calls to [start]/[stop] rather than
 * assuming any particular scope, since its only consumer ([com.bydassistantng.gemini.LiveConversationController])
 * happens to be a singleton itself, giving it one long-lived instance reused across sessions. */
class LivePcmRecorder @Inject constructor() {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val isRecording: Boolean get() = audioRecord != null

    /** True when the mic is the platform's voice-processed source (echo cancellation + gain control), whose
     * levels everything downstream (barge-in bar, faint-sound filter) is calibrated for. */
    @Volatile var usesVoiceProcessing = false
        private set

    /** Starts capturing and delivers each ~50ms chunk to [onChunk] as it's read. [onChunk] runs on
     * a background dispatcher, never the main thread. Returns false if a capture is already
     * running or the platform refused to start one. */
    fun start(onChunk: (ByteArray) -> Unit): Boolean {
        if (audioRecord != null) return false

        val minBufferSize = AudioRecord.getMinBufferSize(INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferSize <= 0) {
            AppLogger.logError(TAG, "AudioRecord.getMinBufferSize returned $minBufferSize — device doesn't support this format")
            return false
        }

        // VOICE_COMMUNICATION first: on this head unit it switches on the platform's echo cancellation and
        // noise suppression, which measured ~10x less speaker echo and cabin noise in the mic than
        // VOICE_RECOGNITION — what makes talking over the assistant workable at all. VOICE_RECOGNITION
        // stays as the fallback so a unit that refuses the first still records.
        val record = SOURCES.firstNotNullOfOrNull { (source, name) ->
            val candidate = try {
                AudioRecord(source, INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2)
            } catch (e: SecurityException) {
                // The permission was revoked between the check at the start of the turn and now.
                AppLogger.logError(TAG, "Microphone permission is not granted ($name)", e)
                null
            } catch (e: Exception) {
                AppLogger.logError(TAG, "Failed to construct AudioRecord ($name)", e)
                null
            }
            when {
                candidate == null -> null
                candidate.state == AudioRecord.STATE_INITIALIZED -> candidate.also {
                    usesVoiceProcessing = source == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    AppLogger.log(TAG, "Microphone source: $name")
                }
                else -> {
                    AppLogger.logError(TAG, "AudioRecord ($name) failed to initialize (state=${candidate.state})")
                    candidate.release()
                    null
                }
            }
        } ?: return false

        try {
            record.startRecording()
        } catch (e: SecurityException) {
            AppLogger.logError(TAG, "Microphone permission was revoked as capture started", e)
            record.release()
            return false
        } catch (e: Exception) {
            AppLogger.logError(TAG, "AudioRecord.startRecording() threw", e)
            record.release()
            return false
        }

        audioRecord = record
        AppLogger.log(TAG, "PCM capture started (${INPUT_SAMPLE_RATE}Hz mono 16-bit)")

        captureJob = scope.launch {
            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            while (isActive) {
                val bytesRead = record.read(buffer, 0, buffer.size)
                when {
                    bytesRead > 0 -> onChunk(buffer.copyOf(bytesRead))
                    bytesRead < 0 -> {
                        AppLogger.logError(TAG, "AudioRecord.read() returned error code $bytesRead")
                        break
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        val wasRecording = audioRecord != null
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // Not recording (e.g. never successfully started) — fine to ignore on the way out.
            }
            it.release()
        }
        audioRecord = null
        if (wasRecording) AppLogger.log(TAG, "PCM capture stopped")
    }
}

/** Streaming raw-PCM playback for the Live API's audio output — see [LivePcmRecorder]'s doc comment
 * for why this isn't `@Singleton` despite ending up with one long-lived instance in practice. */
class LivePcmPlayer @Inject constructor() {
    // The current track. Swapped under [trackLock] only — never held across a write, which can block —
    // because [stopAndFlush] is called from the mic's thread the instant the user talks over a reply,
    // while another thread may be mid-write into the very track being released. (That race crashed the
    // app on a head unit: "Unable to retrieve AudioTrack pointer for write()".)
    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null

    // Bumped by every [stopAndFlush]. A chunk is tagged with the epoch it was *received* in, and dropped
    // if the epoch has moved on by the time it is written — otherwise a chunk that was already in flight
    // when the user cut a reply off would open a fresh track and play a stray syllable of it.
    private val epoch = java.util.concurrent.atomic.AtomicInteger(0)

    /** The epoch to tag a chunk with at the moment it is received. */
    val currentEpoch: Int get() = epoch.get()

    // When everything written so far will have finished playing. The server streams audio faster than
    // real time, so the moment the last chunk arrives is well before the reply stops being audible.
    @Volatile private var playbackEndsAtMs = 0L

    /** One written chunk: when it is expected to be audible, and how loud it is. */
    private class Played(val startMs: Long, val endMs: Long, val rms: Int)

    // The recent past of playback, so the barge-in detector can tell how loud the speakers were at the
    // moment the mic heard something. Guarded by itself.
    private val played = ArrayDeque<Played>()

    /**
     * The loudest playback (RMS of the PCM, 0–32767) expected to be reaching the mic during
     * [fromMs, toMs] (SystemClock.elapsedRealtime). The window is a range rather than an instant because
     * sound takes time to get from the speaker to the mic and the room smears it; 0 if nothing was playing.
     */
    fun referenceRms(fromMs: Long, toMs: Long): Int = synchronized(played) {
        played.filter { it.endMs >= fromMs && it.startMs <= toMs }.maxOfOrNull { it.rms } ?: 0
    }

    // The playback volume, 0..1. Kept here as well as on the track because stopAndFlush releases the track
    // and the next reply's one must start at the same level.
    @Volatile private var volume = 1f

    /** Turns playback up or down at once, including audio already queued (it is applied by the mixer, not
     * baked into the samples). Used to check whether a sound the mic heard was the user or the speakers:
     * the speakers' share falls away with this, the user's does not. */
    fun setVolume(level: Float) {
        volume = level
        val track = synchronized(trackLock) { audioTrack } ?: return
        try {
            track.setVolume(level)
        } catch (_: IllegalStateException) {
            // Released under us by stopAndFlush: the next track takes [volume] when it is created.
        }
    }

    /** How much of the queued reply is still to be heard (0 once it's finished or flushed). */
    fun remainingPlaybackMs(): Long = (playbackEndsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    /** Writes one chunk of 24kHz mono 16-bit PCM for playback, starting the track lazily on first
     * call. `AudioTrack.write()` blocks briefly if its internal buffer is full — call this off the
     * main thread. */
    fun playChunk(pcm: ByteArray, receivedInEpoch: Int = epoch.get()) {
        val track = synchronized(trackLock) {
            if (receivedInEpoch != epoch.get()) return // cut off while this chunk was in flight
            audioTrack ?: createTrack().also { audioTrack = it }
        }
        val written = try {
            track.write(pcm, 0, pcm.size)
        } catch (_: IllegalStateException) {
            // Released from under us by stopAndFlush(): the reply was cut off, so this chunk is moot.
            return
        }
        if (written < 0) return // dead/invalid track, same story
        // 24kHz mono 16-bit = 48 bytes per millisecond.
        val now = SystemClock.elapsedRealtime()
        synchronized(trackLock) {
            if (audioTrack !== track) return // flushed while writing: not going to be heard
            val start = maxOf(playbackEndsAtMs, now)
            val end = start + pcm.size / 48
            playbackEndsAtMs = end
            synchronized(played) {
                played.addLast(Played(start, end, pcm16Rms(pcm)))
                while (played.isNotEmpty() && played.first().endMs < now - PLAYED_HISTORY_MS) played.removeFirst()
            }
        }
    }

    /** Discards any buffered-but-unplayed audio immediately — used on barge-in interruption, where
     * the model's old reply must stop the instant the user starts talking again. Never waits for a write
     * in progress: that write simply fails (and is ignored) once the track is gone. */
    fun stopAndFlush() {
        val track = synchronized(trackLock) {
            epoch.incrementAndGet()
            playbackEndsAtMs = 0
            synchronized(played) { played.clear() }
            audioTrack.also { audioTrack = null }
        }
        track?.let {
            try {
                it.pause()
                it.flush()
                it.stop()
            } catch (_: IllegalStateException) {
                // Already stopped/uninitialized — nothing to flush.
            }
            it.release()
        }
    }

    fun release() = stopAndFlush()

    private fun createTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AppLogger.log(TAG, "Starting PCM playback (${OUTPUT_SAMPLE_RATE}Hz mono 16-bit)")
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.setVolume(volume)
                it.play()
            }
    }
}

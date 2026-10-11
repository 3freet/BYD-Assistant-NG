package com.bydassistantng.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.LoudnessEnhancer
import android.os.SystemClock
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Optional
import javax.inject.Inject

private const val TAG = "LiveAudioIo"

// Fixed by the Live API protocol itself — see GeminiLiveClient's INPUT_AUDIO_MIME and the
// documented output format ("raw 16-bit PCM audio, 24kHz, little-endian"), not a tunable choice.
private const val INPUT_SAMPLE_RATE = 16_000
private const val OUTPUT_SAMPLE_RATE = 24_000
private const val PLAYED_HISTORY_MS = 3_000L
private const val CHUNK_SIZE_BYTES = 1_600 // 50ms of 16kHz mono 16-bit PCM

// What was written to the exterior speaker takes a moment to leave the mixer (the track's own latency is about
// a quarter of a second): the car's switch stays on this much longer than the last sample's expected end, so
// the tail of a sentence is not cut off.
private const val OUTSIDE_TAIL_MS = 450L

// The exterior switch stays on this long after its track is stopped or flushed. What is already in the mixer and
// the amplifier's buffers still plays for a moment (the navi output was seen to stop about 0.3 s after the track
// did), and with the switch already off that tail came out of the cabin's navigation speaker.
private const val OUTSIDE_RELEASE_GRACE_MS = 500L

// Audio that arrives while the car is still switching to the exterior speaker waits for it, up to this long,
// and is then played in the cabin instead: a navi-stream track written before the switch would be heard inside.
private const val OUTSIDE_SWITCH_WAIT_MS = 3_000L

// A track retired when the reply moves between the cabin and the exterior speaker is released this long
// after the last of its audio was due, so it can finish.
private const val RETIRED_TRACK_GRACE_MS = 800L

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
class LivePcmPlayer @Inject constructor(exteriorSpeaker: Optional<ExteriorSpeaker>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Present only in debug builds (see [ExteriorSpeaker]).
    private val exterior: ExteriorSpeaker? = exteriorSpeaker.orElse(null)

    /** Whether this build can play a reply on the car's exterior speaker at all. */
    val canSpeakOutside: Boolean get() = exterior != null

    /** Whether the exterior speaker can be switched to at once (its helper is already running). */
    val outsideReady: Boolean get() = exterior?.isReady == true

    /** The ready-made sounds this build can play on the exterior speaker (none where it has no exterior speaker). */
    val soundEffects: List<SoundEffect> get() = exterior?.soundEffects.orEmpty()

    /** The named sound effect as 24 kHz mono 16-bit PCM, or null when there is none of that name. */
    fun renderEffect(name: String): ShortArray? = exterior?.renderEffect(name)

    /** True from [beginOutside] until the reply has been heard (or cut off): new audio goes outside. */
    @Volatile var speakingOutside = false
        private set

    // The switch to the exterior speaker, while it is being made, and which announcement owns it. A later one
    // takes the switch over, so the end of an earlier one must not turn it off under it.
    private var pendingSwitch: Deferred<Boolean>? = null
    private var outsideGeneration = 0

    // The loudness boost on the exterior track (see [ExteriorVolume]).
    @Volatile private var outsideBoostMb = 0

    // Until when a sound effect is expected to be sounding outside (elapsedRealtime).
    @Volatile private var effectEndsAtMs = 0L

    /**
     * Whether something is being played by the exterior speaker right now: an announcement, or a sound effect and
     * the moment after it. It is loud enough to reach the cabin mic, which must not take it for the user.
     */
    val exteriorSoundActive: Boolean get() = speakingOutside || SystemClock.elapsedRealtime() < effectEndsAtMs

    // The loudness boost attached to each exterior track, released with it. Guarded by [trackLock].
    private val boosts = HashMap<AudioTrack, LoudnessEnhancer>()

    // Tracks that were playing when the reply moved to or from the exterior speaker, still finishing.
    private val retired = ArrayList<AudioTrack>()

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
        awaitOutsideSwitch()
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
            // What goes out of the exterior speaker does not reach the cabin mic, so it is not an echo to expect.
            if (!speakingOutside) {
                synchronized(played) {
                    played.addLast(Played(start, end, pcm16Rms(pcm)))
                    while (played.isNotEmpty() && played.first().endMs < now - PLAYED_HISTORY_MS) played.removeFirst()
                }
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
        track?.let { discard(it) }
        val leftOver = synchronized(trackLock) { retired.toList().also { retired.clear() } }
        leftOver.forEach { discard(it) }
        // A reply that was cut off must not leave the car sending sound outside.
        finishOutside()
    }

    private fun discard(track: AudioTrack) {
        try {
            track.pause()
            track.flush()
            track.stop()
        } catch (_: IllegalStateException) {
            // Already stopped/uninitialized — nothing to flush.
        }
        releaseWithBoost(track)
    }

    /** Releases [track] and the loudness boost that was attached to it, if any. */
    private fun releaseWithBoost(track: AudioTrack) {
        val boost = synchronized(trackLock) { boosts.remove(track) }
        runCatching { boost?.release() }
        runCatching { track.release() }
    }

    /** A loudness boost of [boostMb] millibels on [track]'s audio, or null when none is wanted or it would not attach. */
    private fun attachBoost(track: AudioTrack, boostMb: Int): LoudnessEnhancer? {
        if (boostMb <= 0) return null
        return try {
            LoudnessEnhancer(track.audioSessionId).apply {
                setTargetGain(boostMb)
                enabled = true
            }
        } catch (e: Exception) {
            AppLogger.logError(TAG, "Could not boost the exterior track", e)
            null
        }
    }

    fun release() = stopAndFlush()

    /** Starts getting the exterior speaker ready (nothing is switched), so an announcement starts sooner. */
    fun prepareOutside() {
        exterior?.prepare()
    }

    /**
     * Makes what is played from now on heard outside only, at [volume]. Whatever is still playing finishes
     * in the cabin on its own track. With [waitForSwitch] false this returns at once and the audio that
     * follows waits for the car to switch (see [playChunk]), which hides the switch behind the model
     * starting to speak; true returns only once the car has switched, and reports whether it did.
     * False (and nothing changed) when this build has no exterior speaker or the car would not switch.
     */
    suspend fun beginOutside(volume: ExteriorVolume, waitForSwitch: Boolean): Boolean {
        val speaker = exterior ?: return false
        val switching: Deferred<Boolean> = synchronized(trackLock) {
            outsideGeneration++
            outsideBoostMb = volume.boostMb
            val already = pendingSwitch
            if (speakingOutside && already != null) {
                already // announcing already: keep the switch it has
            } else {
                retireTrackLocked()
                speakingOutside = true
                val opening = scope.async {
                    val opened = try {
                        speaker.open()
                    } catch (e: Exception) {
                        AppLogger.logError(TAG, "Exterior speaker would not open", e)
                        false
                    }
                    if (!opened) {
                        synchronized(trackLock) {
                            speakingOutside = false
                            pendingSwitch = null
                        }
                    }
                    opened
                }
                pendingSwitch = opening
                opening
            }
        }
        if (waitForSwitch) {
            val opened = switching.await()
            if (opened) AppLogger.log(TAG, "Replies now play on the exterior speaker")
            return opened
        }
        return true
    }

    /** Blocks the writer of a chunk while the car is still switching to the exterior speaker. */
    private fun awaitOutsideSwitch() {
        val switching = synchronized(trackLock) { if (speakingOutside) pendingSwitch else null } ?: return
        if (switching.isCompleted) return
        val opened = runBlocking { withTimeoutOrNull(OUTSIDE_SWITCH_WAIT_MS) { switching.await() } }
        if (opened != true) {
            AppLogger.log(TAG, "The exterior speaker did not switch in time — this reply plays in the cabin")
            val gaveUp = synchronized(trackLock) {
                (pendingSwitch === switching).also {
                    if (it) {
                        speakingOutside = false
                        pendingSwitch = null
                    }
                }
            }
            // If it does switch after all, it is put back.
            if (gaveUp) closeAfterGrace(switching)
        }
    }

    /**
     * Puts the car back once [OUTSIDE_RELEASE_GRACE_MS] has passed and the switch (if it was still being made)
     * has finished — and only if it did switch: one that never happened has nothing to put back.
     */
    private fun closeAfterGrace(switching: Deferred<Boolean>?) {
        scope.launch {
            delay(OUTSIDE_RELEASE_GRACE_MS)
            val opened = switching?.let { runCatching { it.await() }.getOrDefault(false) } ?: true
            if (opened) exterior?.close()
        }
    }

    /** Waits until everything written has been heard, then puts the car back. The next reply is the cabin's. */
    suspend fun endOutside() {
        val generation = synchronized(trackLock) { if (!speakingOutside) return else outsideGeneration }
        delay(remainingPlaybackMs() + OUTSIDE_TAIL_MS)
        // A newer announcement took the switch over while this one finished: it ends it, not this.
        if (synchronized(trackLock) { outsideGeneration != generation }) return
        finishOutside()
    }

    private fun finishOutside() {
        val switching = synchronized(trackLock) {
            if (!speakingOutside) return
            speakingOutside = false
            val pending = pendingSwitch
            pendingSwitch = null
            retireTrackLocked()
            pending
        }
        AppLogger.log(TAG, "Replies are back on the cabin speakers")
        closeAfterGrace(switching)
    }

    /**
     * Plays [pcm] (24 kHz mono 16-bit) on the exterior speaker only, on a track of its own, so the
     * assistant's replies carry on in the cabin meanwhile. Returns when it has been heard; false when
     * there is no exterior speaker or the car would not switch to it.
     */
    suspend fun playEffectOutside(pcm: ShortArray, volume: ExteriorVolume): Boolean {
        val speaker = exterior ?: return false
        if (!speaker.open()) return false
        var track: AudioTrack? = null
        var boost: LoudnessEnhancer? = null
        try {
            val minBuffer = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = speaker.newTrack(OUTPUT_SAMPLE_RATE, maxOf(minBuffer * 2, 9_600))
            boost = attachBoost(track, volume.boostMb)
            val startedAt = SystemClock.elapsedRealtime()
            // The effect (and its tail) is loud outside; the cabin mic must not take it for the user.
            effectEndsAtMs = startedAt + pcm.size * 1000L / OUTPUT_SAMPLE_RATE + OUTSIDE_TAIL_MS + OUTSIDE_RELEASE_GRACE_MS
            track.play()
            withContext(Dispatchers.IO) {
                var offset = 0
                while (offset < pcm.size) {
                    val written = track.write(pcm, offset, minOf(2_400, pcm.size - offset))
                    if (written <= 0) break
                    offset += written
                }
            }
            val endsAt = startedAt + pcm.size * 1000L / OUTPUT_SAMPLE_RATE
            delay((endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) + OUTSIDE_TAIL_MS)
            return true
        } finally {
            runCatching { track?.stop() }
            withContext(NonCancellable) {
                // Let what the mixer and the amplifier still hold play out before the switch goes off.
                delay(OUTSIDE_RELEASE_GRACE_MS)
                runCatching { boost?.release() }
                runCatching { track?.release() }
                speaker.close()
            }
        }
    }

    /** Lets the current track play out and stops using it, so the next chunk starts a track of the right kind.
     * Called with [trackLock] held. */
    private fun retireTrackLocked() {
        val track = audioTrack ?: return
        audioTrack = null
        retired.add(track)
        val waitMs = remainingPlaybackMs() + RETIRED_TRACK_GRACE_MS
        try {
            track.stop() // a streaming track plays out what was written, then stops
        } catch (_: IllegalStateException) {
            // Already stopped.
        }
        scope.launch {
            delay(waitMs)
            val stillRetired = synchronized(trackLock) { retired.remove(track) }
            if (stillRetired) releaseWithBoost(track)
        }
    }

    private fun createTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outside = exterior.takeIf { speakingOutside }
        if (outside != null) {
            AppLogger.log(TAG, "Starting PCM playback on the exterior speaker (${OUTPUT_SAMPLE_RATE}Hz mono 16-bit)")
            return outside.newTrack(OUTPUT_SAMPLE_RATE, minBufferSize * 2).also {
                it.setVolume(volume)
                attachBoost(it, outsideBoostMb)?.let { boost -> boosts[it] = boost } // called with [trackLock] held
                it.play()
            }
        }
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

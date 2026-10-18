package com.bydassistantng.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.bydassistantng.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
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
private const val KEY_LOWERED_FROM = "lowered_from"
private const val KEY_LOWERED_TO = "lowered_to"
private const val KEY_NAVI_FROM = "navi_from"
private const val KEY_NAVI_TO = "navi_to"

// After a conversation: how long past the last sound the navigation volume stays where the reply left it before it is put back.
private const val NAVI_RESTORE_HOLD_MS = 1_500L

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
// How long the switch itself takes to go off, once the grace is over (the route counts as in use until it has).
private const val OUTSIDE_RELEASE_SWITCH_MS = 100L
// What a reply's tail is given, past the last sample, before the exterior switch may go on (the headrest's stream is shared).
private const val HEADREST_TAIL_MS = 300L

// How long a reply in the headrest waits for the exterior route to be free, and how often it looks.
private const val HEADREST_EXTERIOR_WAIT_MS = 8_000L
private const val EXTERIOR_POLL_MS = 40L

// Audio that arrives while the car is still switching to the exterior speaker waits for it, up to this long,
// and is then played in the cabin instead: a navi-stream track written before the switch would be heard inside.
private const val OUTSIDE_SWITCH_WAIT_MS = 3_000L

// A track retired when the reply moves between the cabin and the exterior speaker is released this long
// after the last of its audio was due, so it can finish.
private const val RETIRED_TRACK_GRACE_MS = 800L

// On the voice channel, the music is held down this long after the last word, so a short gap between two parts of a
// reply does not let it back up and down again; sound that starts after a longer silence than NEW_REPLY_GAP_MS is a
// new reply, which sets the volume and the music afresh.
private const val VOICE_FOCUS_TAIL_MS = 700L
private const val NEW_REPLY_GAP_MS = 600L

// When the user cuts a reply off, the music stays down this long for what is about to follow (their words, then the
// answer), so it does not come back up for a moment and go down again.
private const val MUSIC_CUT_OFF_HOLD_MS = 4_000L

// The music is turned down and brought back in index steps over about this long (see [MusicDip.rampStepMs]).

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
class LivePcmPlayer @Inject constructor(
    exteriorSpeaker: Optional<ExteriorSpeaker>,
    @ApplicationContext context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    /** How replies use the car's voice channel; set when a conversation starts. */
    @Volatile var voiceChannel = VoiceChannelOptions.OFF

    // The audio focus held for a reply on the voice channel, and the job that gives it up once the reply has been
    // heard. Guarded by [focusLock].
    private val focusLock = Any()
    private var voiceFocus: AudioFocusRequest? = null
    private var focusJob: Job? = null

    // The media volume while it is turned down by a share (see [MusicDuck.LEVEL]): what it was and what it was set to.
    // Guarded by [focusLock].
    private var loweredFrom: Int? = null
    private var loweredTo: Int? = null
    private var restoreJob: Job? = null
    private var restoringTo: Int? = null
    private var rampDownJob: Job? = null
    private var rampDownActive = false

    // The navigation volume while the headrest's reply follows the cabin noise: what it was and what it was set to.
    // Guarded by [focusLock].
    private var naviFrom: Int? = null
    private var naviTo: Int? = null
    private var naviRestoreJob: Job? = null

    // Bumped whenever a reply starts. The job that gives the music back remembers the number it was started under and
    // gives up if it has changed, so a reply that starts just as the last one's hold runs out keeps the music down.
    private var holdGeneration = 0

    private val voicePrefs = context.getSharedPreferences("voice_channel", Context.MODE_PRIVATE)

    /** The cabin noise now in dBFS, or null; set by the conversation, which is the one listening to the cabin. */
    @Volatile var cabinNoise: (() -> Double?)? = null

    /**
     * How much quieter than the voice stream's volume the replies are made, 0..1 (1 is not at all): for a calm cabin,
     * where the stream is already at its lowest index and the volume has to be taken off the track itself.
     */
    @Volatile var voiceBaseVolume = 1f
        private set

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

    /**
     * Whether the exterior speaker's route is on, or has only just been told to go off. Replies and tones that share its
     * stream (the headrest speaker's) wait for it to be over.
     */
    val exteriorInUse: Boolean
        get() = speakingOutside || effectsOutside.get() > 0 ||
            SystemClock.elapsedRealtime() < outsideReleaseUntilMs || SystemClock.elapsedRealtime() < effectEndsAtMs

    /** True from [beginOutside] until the reply has been heard (or cut off): new audio goes outside. */
    @Volatile var speakingOutside = false
        private set

    // The switch to the exterior speaker, while it is being made, and which announcement owns it. A later one
    // takes the switch over, so the end of an earlier one must not turn it off under it.
    private var pendingSwitch: Deferred<Boolean>? = null
    private var outsideGeneration = 0

    // Until when the exterior route is still in use after an announcement (its switch goes off a moment after the last
    // sound), elapsedRealtime. With the headrest as the speaker the replies and tones share the exterior speaker's stream,
    // so they must not start while it is on: they would be heard outside.
    @Volatile private var outsideReleaseUntilMs = 0L

    // The loudness boost on the exterior track (see [ExteriorVolume]).
    @Volatile private var outsideBoostMb = 0

    // Until when a sound effect is expected to be sounding outside (elapsedRealtime).
    @Volatile private var effectEndsAtMs = 0L

    // How many sound effects are on the exterior route, from before its switch goes on until it has gone off again. A
    // reply in the headrest shares that route's stream, so it waits for this to be zero.
    private val effectsOutside = java.util.concurrent.atomic.AtomicInteger(0)

    // The epoch of a reply that stopped waiting for the exterior route (see [awaitExteriorFree]); -1 when none.
    @Volatile private var exteriorWaitGaveUpEpoch = -1

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
        applyTrackVolume()
    }

    /** What the cabin track plays at: the dip asked for by the conversation, less what a calm cabin takes off. */
    private fun cabinTrackVolume(): Float = if (voiceChannel.enabled) volume * voiceBaseVolume else volume

    private fun applyTrackVolume() {
        val track = synchronized(trackLock) { audioTrack } ?: return
        try {
            track.setVolume(if (speakingOutside) volume else cabinTrackVolume())
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
        awaitExteriorFree(receivedInEpoch)
        // The first sound of each reply on the voice channel: the music is dealt with and the volume set before it
        // starts, every time, so a cabin that changed between two replies is heard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && voiceChannel.enabled && !speakingOutside && startsNewReply()) {
            prepareVoiceReply(receivedInEpoch)
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holdVoiceFocusUntilHeard()
    }

    /** Whether the chunk about to be written starts a reply: there is no track yet, or what was written has been heard. */
    private fun startsNewReply(): Boolean = synchronized(trackLock) {
        audioTrack == null || SystemClock.elapsedRealtime() - playbackEndsAtMs > NEW_REPLY_GAP_MS
    }

    /**
     * How much of a cabin-noise reading taken just now is the music, which the conversation is about to turn down (see
     * [VoiceVolume.musicAllowanceDb]); 0 when no music is playing or it is left as it is.
     */
    fun musicAllowanceDb(): Double {
        val options = voiceChannel
        if (!options.enabled || !MusicPlayback.isPlaying(audioManager)) return 0.0
        return VoiceVolume.musicAllowanceDb(options.music, options.dipPercent)
    }

    /** Whether the whole of this conversation is being handled (see [beginConversationMusic]), not only the replies. */
    @Volatile private var musicHeldForConversation = false

    /**
     * A conversation has opened: the music is dealt with from now until the conversation ends, so it is already down
     * while the assistant listens (the user hears themselves and the microphone hears less of it) and does not go up
     * and down between turns.
     */
    fun beginConversationMusic() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !voiceChannel.enabled) return
        musicHeldForConversation = true
        synchronized(focusLock) { holdGeneration++ }
        quietenMusic(voiceChannel)
    }

    /** The conversation is over: the music comes back. */
    fun endConversationMusic() {
        musicHeldForConversation = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            synchronized(focusLock) {
                focusJob?.cancel()
                holdGeneration++
            }
            releaseMusic()
        }
    }

    /**
     * Before the first sound of a reply on the voice channel: makes sure the music is dealt with (it already is, from
     * the start of the conversation, unless it was not playing then) and, when the volume follows the cabin, sets the
     * volume from the cabin noise heard so far (see [CabinNoiseMonitor]), so nothing waits.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun prepareVoiceReply(receivedInEpoch: Int) {
        val options = voiceChannel
        if (receivedInEpoch != epoch.get()) return
        synchronized(focusLock) { holdGeneration++ }
        quietenMusic(options)
        adaptVolume()
    }

    /**
     * Sets the volume of the voice, and of the tones that go with it, for the cabin as it is now: the voice stream's
     * (see [VoiceVolume.levelFor]) or, with the headrest as the speaker, the navigation stream's (see
     * [VoiceVolume.headrestLevelFor]). The navigation volume is the one the car's navigation prompts use, so it is
     * written down when it is first changed in a conversation and put back afterwards ([endConversationVolume]); with the
     * cabin-noise volume off the volume is left as it is. A [noise] reading can be given (the one taken while the
     * conversation was opening); otherwise the monitor's is used.
     *
     * Returns false when the volume is meant to follow the cabin and there is no noise reading to go by yet.
     */
    fun adaptVolume(noise: Double? = null, quiet: Boolean = false): Boolean {
        val options = voiceChannel
        if (!options.enabled) return true
        val headrest = options.speaker == VoiceSpeaker.HEADREST
        val stream = if (headrest) VoiceChannel.NAVI_STREAM else VoiceChannel.STREAM
        if (!options.adaptive) {
            voiceBaseVolume = 1f
            if (headrest && !quiet) {
                val current = audioManager.getStreamVolume(stream)
                AppLogger.log(TAG, "Reply on the headrest speaker, at the navigation volume $current" + if (current == 0) " (silent: the navigation volume is 0)" else "")
            }
            return true
        }
        val reading = noise ?: cabinNoise?.invoke()
        if (reading == null) {
            if (!quiet) AppLogger.log(TAG, "No cabin noise reading yet — the voice volume stays where it is")
            return false
        }
        val min = VoiceChannel.minIndex(audioManager, stream)
        val max = audioManager.getStreamMaxVolume(stream)
        val level = if (headrest) {
            VoiceVolume.headrestLevelFor(reading, options.gainSteps, min, max)
        } else {
            VoiceVolume.levelFor(reading, options.gainSteps, min, max)
        }
        val before = audioManager.getStreamVolume(stream)
        if (headrest) rememberNaviVolume(before, level.index)
        runCatching { audioManager.setStreamVolume(stream, level.index, 0) }
            .onFailure { AppLogger.log(TAG, "Could not set the voice volume: $it") }
        voiceBaseVolume = level.trackVolume
        applyTrackVolume()
        val taken = if (level.attenuationDb > 0) ", ${"%.1f".format(level.attenuationDb)} dB off the track" else ""
        val what = if (headrest) "Headrest volume" else "Voice volume"
        AppLogger.log(TAG, "$what $before -> ${level.index}$taken for a cabin at ${"%.1f".format(reading)} dBFS (gain ${options.gainSteps}, range $min..$max)")
        return true
    }

    /** Writes down what the navigation volume was before the app first moves it in a conversation, and what it moves it to. */
    private fun rememberNaviVolume(current: Int, to: Int) {
        synchronized(focusLock) {
            naviRestoreJob?.cancel()
            naviRestoreJob = null
            if (naviFrom == null) {
                // What the driver had is what comes back. Kept on disk too.
                naviFrom = current
                voicePrefs.edit().putInt(KEY_NAVI_FROM, current).apply()
            }
            naviTo = to
            voicePrefs.edit().putInt(KEY_NAVI_TO, to).apply()
        }
    }

    /** The conversation is over: once its last sound has been heard, the navigation volume goes back to what the driver had. */
    fun endConversationVolume() {
        synchronized(focusLock) {
            if (naviFrom == null) return
            naviRestoreJob?.cancel()
            naviRestoreJob = scope.launch {
                delay(remainingPlaybackMs() + NAVI_RESTORE_HOLD_MS)
                restoreNaviVolume()
            }
        }
    }

    /** Puts the navigation volume back, unless the driver has changed it since the app set it. */
    private fun restoreNaviVolume() {
        val (from, to) = synchronized(focusLock) {
            val from = naviFrom ?: return
            val to = naviTo ?: return
            naviFrom = null
            naviTo = null
            naviRestoreJob = null
            voicePrefs.edit().remove(KEY_NAVI_FROM).remove(KEY_NAVI_TO).apply()
            from to to
        }
        val current = audioManager.getStreamVolume(VoiceChannel.NAVI_STREAM)
        if (current != to) {
            AppLogger.log(TAG, "The navigation volume is $current, not $to: it was changed meanwhile, so it stays")
            return
        }
        runCatching { audioManager.setStreamVolume(VoiceChannel.NAVI_STREAM, from, 0) }
        AppLogger.log(TAG, "Navigation volume back to $from")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun quietenMusic(options: VoiceChannelOptions) {
        when (options.music) {
            MusicDuck.KEEP -> Unit
            MusicDuck.LOWER -> requestVoiceFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, options.music)
            MusicDuck.PAUSE -> requestVoiceFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, options.music)
            MusicDuck.LEVEL -> lowerMusicVolume(options.dipPercent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestVoiceFocus(gain: Int, music: MusicDuck) {
        synchronized(focusLock) {
            if (voiceFocus != null) return
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(VoiceChannel.attributes())
                .setOnAudioFocusChangeListener { change -> AppLogger.log(TAG, "Voice focus changed: $change") }
                .build()
            val result = audioManager.requestAudioFocus(request)
            AppLogger.log(TAG, "Voice focus requested (${music.key}): $result")
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) voiceFocus = request
        }
    }

    /**
     * Turns the media volume down by [percent], in a few quick steps, to be put back when the conversation ends. Only
     * while music is playing: with nothing to turn down the volume is left alone.
     */
    private fun lowerMusicVolume(percent: Int) {
        synchronized(focusLock) {
            // A reply that follows closely on another: what the last one was still bringing back is back at once, so
            // the volume to return to is the user's, not a step on the way up.
            restoreJob?.cancel()
            restoringTo?.let { runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) } }
            restoringTo = null
            if (loweredFrom != null) return
            if (!MusicPlayback.isPlaying(audioManager)) {
                AppLogger.log(TAG, "No music is playing — nothing to turn down")
                return
            }
            val now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = MusicDip.target(now, percent)
            if (target >= now) {
                AppLogger.log(TAG, "The music is at $now: too low to turn down")
                return
            }
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, now - 1, 0) }
                .onFailure {
                    AppLogger.log(TAG, "Could not turn the music down: $it")
                    return
                }
            loweredFrom = now
            loweredTo = target
            // Kept on disk too: if the app dies before the conversation is over, the next one puts it back.
            voicePrefs.edit().putInt(KEY_LOWERED_FROM, now).putInt(KEY_LOWERED_TO, target).apply()
            AppLogger.log(TAG, "Music volume $now -> $target ($percent% down)")
            // The rest of the way in quick steps, so it is a short fade and not a drop.
            rampDownActive = true
            rampDownJob = scope.launch {
                val stepMs = MusicDip.rampStepMs(now - target)
                var level = now - 1
                while (isActive && level > target) {
                    delay(stepMs)
                    level--
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
                }
                synchronized(focusLock) { rampDownActive = false }
            }
        }
    }

    /** Puts the media volume back one step at a time, unless the user has changed it meanwhile. */
    private fun restoreMusicVolume() {
        val (from, to, rampingDown) = synchronized(focusLock) {
            val from = loweredFrom ?: return
            val to = loweredTo ?: return
            loweredFrom = null
            loweredTo = null
            voicePrefs.edit().remove(KEY_LOWERED_FROM).remove(KEY_LOWERED_TO).apply()
            // Cut short, a fade down is somewhere between the two.
            rampDownJob?.cancel()
            Triple(from, to, rampDownActive.also { rampDownActive = false })
        }
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (if (rampingDown) current !in to..from else current != to) {
            AppLogger.log(TAG, "The music volume is $current, not $to: it was changed meanwhile, so it stays")
            return
        }
        synchronized(focusLock) {
            restoreJob?.cancel()
            restoringTo = from
            val stepMs = MusicDip.rampStepMs(from - current)
            restoreJob = scope.launch {
                var level = current
                while (isActive && level < from) {
                    level++
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
                    delay(stepMs)
                }
                if (isActive) {
                    synchronized(focusLock) { if (restoringTo == from) restoringTo = null }
                    AppLogger.log(TAG, "Music volume back to $from")
                }
            }
        }
    }

    /**
     * After a conversation was cut short while the music was turned down (the app was stopped mid-conversation): puts
     * it back, if it is still where the app left it.
     */
    fun recoverMusicVolume() {
        recoverNaviVolume()
        val from = voicePrefs.getInt(KEY_LOWERED_FROM, -1)
        val to = voicePrefs.getInt(KEY_LOWERED_TO, -1)
        if (from < 0 || to < 0) return
        voicePrefs.edit().remove(KEY_LOWERED_FROM).remove(KEY_LOWERED_TO).apply()
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == to) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, from, 0) }
            AppLogger.log(TAG, "The music was still turned down from an earlier conversation — put back to $from")
        }
    }

    /** Likewise for the navigation volume: a conversation cut short while it was moved for the headrest's reply. */
    private fun recoverNaviVolume() {
        synchronized(focusLock) { if (naviFrom != null) return } // this run's own, put back when its conversation ends
        val from = voicePrefs.getInt(KEY_NAVI_FROM, -1)
        val to = voicePrefs.getInt(KEY_NAVI_TO, -1)
        if (from < 0 || to < 0) return
        voicePrefs.edit().remove(KEY_NAVI_FROM).remove(KEY_NAVI_TO).apply()
        if (audioManager.getStreamVolume(VoiceChannel.NAVI_STREAM) == to) {
            runCatching { audioManager.setStreamVolume(VoiceChannel.NAVI_STREAM, from, 0) }
            AppLogger.log(TAG, "The navigation volume was still where an earlier conversation left it — put back to $from")
        }
    }

    /** Waits until what has been written has been heard, and a moment more; each chunk pushes that back. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun holdVoiceFocusUntilHeard() {
        synchronized(focusLock) {
            if (!voiceChannel.enabled) return
            focusJob?.cancel()
            val generation = holdGeneration
            focusJob = scope.launch {
                while (isActive) {
                    delay(remainingPlaybackMs() + VOICE_FOCUS_TAIL_MS)
                    if (remainingPlaybackMs() == 0L) break
                }
                finishReply(generation)
            }
        }
    }

    /**
     * A reply has been heard. Its track is let go, and, unless the music is being held for the whole conversation, the
     * music comes back. Does nothing if another reply has started since [generation].
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun finishReply(generation: Int) {
        synchronized(focusLock) {
            if (generation != holdGeneration) return
            focusJob = null
        }
        if (!musicHeldForConversation) releaseMusic()
        retireIdleVoiceTrack()
    }

    /** Gives the music back: the focus, and the volume it was turned down from. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun releaseMusic() {
        val request = synchronized(focusLock) { voiceFocus.also { voiceFocus = null } }
        if (request != null) {
            val result = audioManager.abandonAudioFocusRequest(request)
            AppLogger.log(TAG, "Voice focus given back: $result")
        }
        restoreMusicVolume()
    }

    /**
     * Once a reply has been heard its track is let go, so that the voice stream is only in use while the assistant
     * speaks: a track that stays open between replies keeps the stream active (the car shows it as playing, and the
     * volume keys follow it) for the whole conversation.
     */
    private fun retireIdleVoiceTrack() {
        synchronized(trackLock) {
            if (!voiceChannel.enabled || speakingOutside || remainingPlaybackMs() > 0 || audioTrack == null) return
            retireTrackLocked()
        }
    }

    /** The reply was cut off outside a held conversation: the music stays down a few seconds for what follows. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun holdMusicAfterCutOff() {
        synchronized(focusLock) {
            if (!voiceChannel.enabled || (voiceFocus == null && loweredFrom == null)) return
            focusJob?.cancel()
            val generation = holdGeneration
            focusJob = scope.launch {
                delay(MUSIC_CUT_OFF_HOLD_MS)
                synchronized(focusLock) {
                    if (generation != holdGeneration) return@launch
                    focusJob = null
                }
                releaseMusic()
            }
        }
    }

    /** Discards any buffered-but-unplayed audio immediately — used on barge-in interruption, where
     * the model's old reply must stop the instant the user starts talking again. Never waits for a write
     * in progress: that write simply fails (and is ignored) once the track is gone. With [keepMusicDown] the
     * conversation goes on, so the music is not brought back (it is held for the whole conversation, or for a few
     * seconds if not); without it the conversation is over and the music comes back. */
    fun stopAndFlush(keepMusicDown: Boolean = false) {
        val track = synchronized(trackLock) {
            epoch.incrementAndGet()
            playbackEndsAtMs = 0
            synchronized(played) { played.clear() }
            audioTrack.also { audioTrack = null }
        }
        track?.let { discard(it) }
        val leftOver = synchronized(trackLock) { retired.toList().also { retired.clear() } }
        leftOver.forEach { discard(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!keepMusicDown) {
                endConversationMusic()
            } else if (!musicHeldForConversation) {
                holdMusicAfterCutOff()
            }
        }
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
                    letHeadrestReplyFinish()
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

    /**
     * With the headrest as the speaker, a reply that is still playing is on the stream the exterior switch is about to send
     * outside: it is let finish first. Nothing is waited for when nothing is playing.
     */
    private suspend fun letHeadrestReplyFinish() {
        if (!voiceChannel.enabled || voiceChannel.speaker != VoiceSpeaker.HEADREST) return
        val tail = remainingPlaybackMs()
        if (tail > 0) delay(tail + HEADREST_TAIL_MS)
    }

    /**
     * With the headrest as the speaker, the reply is on the stream the exterior route sends outside: while a sound effect
     * (or the end of an announcement) has the route, the reply waits for it, or its first words would be heard outside the
     * car. Returns early if the reply is cut off meanwhile.
     */
    private fun awaitExteriorFree(epochAtStart: Int) {
        if (!voiceChannel.enabled || voiceChannel.speaker != VoiceSpeaker.HEADREST) return
        if (speakingOutside) return
        if (!exteriorInUse) {
            exteriorWaitGaveUpEpoch = -1
            return
        }
        // A reply that has already given up waiting does not start the wait over with each of its chunks.
        if (exteriorWaitGaveUpEpoch == epochAtStart) return
        AppLogger.log(TAG, "The exterior speaker is in use — the reply waits for it (the headrest shares its stream)")
        val deadline = SystemClock.elapsedRealtime() + HEADREST_EXTERIOR_WAIT_MS
        while (exteriorInUse && !speakingOutside && epoch.get() == epochAtStart && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(EXTERIOR_POLL_MS)
        }
        if (exteriorInUse && !speakingOutside && epoch.get() == epochAtStart) {
            // Better the first words of a reply heard outside than an assistant that stays silent because the route is stuck.
            exteriorWaitGaveUpEpoch = epochAtStart
            AppLogger.log(TAG, "The exterior speaker is still in use after ${HEADREST_EXTERIOR_WAIT_MS / 1000} s — the reply plays anyway")
        }
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
    private fun closeAfterGrace(switching: Deferred<Boolean>?): Job {
        outsideReleaseUntilMs = SystemClock.elapsedRealtime() + OUTSIDE_RELEASE_GRACE_MS + OUTSIDE_RELEASE_SWITCH_MS
        return scope.launch {
            delay(OUTSIDE_RELEASE_GRACE_MS)
            val opened = switching?.let { runCatching { it.await() }.getOrDefault(false) } ?: true
            if (opened) exterior?.close()
            outsideReleaseUntilMs = 0L
        }
    }

    /** Waits until everything written has been heard, then puts the car back. The next reply is the cabin's. */
    suspend fun endOutside() {
        val generation = synchronized(trackLock) { if (!speakingOutside) return else outsideGeneration }
        delay(remainingPlaybackMs() + OUTSIDE_TAIL_MS)
        // A newer announcement took the switch over while this one finished: it ends it, not this.
        if (synchronized(trackLock) { outsideGeneration != generation }) return
        val released = finishOutside()
        // The headrest's tones and replies share the exterior speaker's stream: nothing of theirs starts until it is off.
        if (voiceChannel.enabled && voiceChannel.speaker == VoiceSpeaker.HEADREST) released?.join()
    }

    private fun finishOutside(): Job? {
        val switching = synchronized(trackLock) {
            if (!speakingOutside) return null
            speakingOutside = false
            val pending = pendingSwitch
            pendingSwitch = null
            retireTrackLocked()
            pending
        }
        AppLogger.log(TAG, "Replies are back on the cabin speakers")
        return closeAfterGrace(switching)
    }

    /**
     * Plays [pcm] (24 kHz mono 16-bit) on the exterior speaker only, on a track of its own, so the
     * assistant's replies carry on in the cabin meanwhile. Returns when it has been heard; false when
     * there is no exterior speaker or the car would not switch to it.
     */
    suspend fun playEffectOutside(pcm: ShortArray, volume: ExteriorVolume): Boolean {
        val speaker = exterior ?: return false
        effectsOutside.incrementAndGet()
        try {
            return playEffectOn(speaker, pcm, volume)
        } finally {
            effectsOutside.decrementAndGet()
        }
    }

    private suspend fun playEffectOn(speaker: ExteriorSpeaker, pcm: ShortArray, volume: ExteriorVolume): Boolean {
        letHeadrestReplyFinish()
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
        val onVoiceChannel = voiceChannel.enabled
        AppLogger.log(TAG, "Starting PCM playback${if (onVoiceChannel) " on the voice channel" else ""} (${OUTPUT_SAMPLE_RATE}Hz mono 16-bit)")
        return AudioTrack.Builder()
            .setAudioAttributes(
                if (onVoiceChannel) {
                    VoiceChannel.attributes(voiceChannel.speaker)
                } else {
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                },
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
                it.setVolume(cabinTrackVolume())
                it.play()
            }
    }
}

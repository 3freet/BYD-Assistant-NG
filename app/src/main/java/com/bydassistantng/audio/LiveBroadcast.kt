package com.bydassistantng.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.SystemClock
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.util.AppLogger
import com.bydassistantng.util.hasMicPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

private const val TAG = "LiveBroadcast"

// The longest one broadcast goes on, a little under what the helper that holds the switch allows ([ExteriorSpeakerGuard.MAX_ON_MS]),
// so that it ends in the app's way (with its tone and its words) and not by the helper switching the route off under it.
private const val MAX_LIVE_MS = 55_000L

// A squeal takes this much off the gain of the voice (a tenth of the power: 10 dB); a second one within the window, or one that would leave
// next to nothing, ends the broadcast.
private const val SQUEAL_GAIN_FACTOR = 0.316f
private const val MIN_GUARD_FACTOR = 0.12f
private const val FEEDBACK_REPEAT_WINDOW_MS = 15_000L

// The track holds at least this much (160 ms of 16 kHz mono), as the exterior sound apps give theirs: it is what keeps the sound from running
// dry and breaking up when the microphone's chunks come a little late. How much of it must be full before the sound starts is another matter
// (see [TRACK_START_MS]): the platform's own choice is all of it, which is a delay of its own.
private const val TRACK_MIN_BUFFER_BYTES = 5_120

// The sound starts playing once this much of it is waiting in the track (the buffer holds 160 ms; the platform's own choice is to wait until
// it is full, and the sound then stays that far behind for as long as it goes on). The microphone's chunks come every 10 ms on a thread of audio
// priority, so a few of them in hand are all a steady flow needs: 60 ms ran dry once at the start of nearly every broadcast on the car, 80 did not.
// A track that does run dry is given [TRACK_START_STEP_MS] more each time.
private const val TRACK_START_MS = 80
private const val TRACK_START_STEP_MS = 20
private const val TRACK_START_MAX_MS = 160

// The navigation route's track, whose mixer reads in larger periods, ran dry two or three times a second at 80 ms and 40 ms (measured on the car with
// silence: a queue of 295 ms with the underruns, or 355 ms without, depending on how the start fell), and not at all from 120 ms on (403 ms): it starts
// there. That route is half a second or so behind anyway.
private const val NAV_TRACK_START_MS = 120

// The music's stop is waited for: how often it is looked at, how long its players are given to say that they have paused (the car's own media center
// took 0.12 s one time and 0.54 s the next), and how long the sound a paused player had already handed to the car's audio path is given to be heard
// in the cabin, where it belongs, before the route is switched.
private const val MUSIC_POLL_MS = 20L
private const val MUSIC_STOP_WAIT_MS = 1_500L

// A microphone that gives no chunk for this long has been lost (the record died without saying so): looked at this often while live.
private const val MIC_WATCH_MS = 500L
private const val MIC_SILENT_MS = 2_000L
private const val MUSIC_DRAIN_MS = 200L

// How long the route is held after the voice is cut at the end, for what is already in the car's mixer and amplifier to come out (the cut itself
// is at once). The navigation stream's route, which is deeper, is held as long as an announcement's ([OUTSIDE_RELEASE_GRACE_MS]).
private const val LIVE_DRAIN_MS = 320L

// A line about the levels is written this often, which is what to look at when the voice is faded too soon or the cabin comes through.
private const val LEVEL_LOG_CHUNKS = 200 // two seconds of 10 ms chunks

// What the track's buffering looks like is logged this far into each route of a broadcast.
private const val LATENCY_LOG_CHUNKS = 100

// How often the track is looked at for running dry, and whether music is playing in the cabin (the fade then goes by the background).
private const val START_CHECK_CHUNKS = 50
private const val MUSIC_CHECK_CHUNKS = 50

// The navigation route's track is given this much silence before the first sound: it then starts with it queued, in the stable state it was
// measured to settle into (a queue of 355 to 405 ms, no underruns) rather than in the one it ran dry in two or three times a second; it ran dry
// six to nine times in the first moments of a route switch on the car without it, which the silence absorbs instead of the voice.
private const val NAV_PREFILL_MS = 150

// The level meter gets a frame this often (100 ms of 10 ms chunks).
private const val LEVEL_FRAME_CHUNKS = 10

// A microphone chunk that comes later than this after the one before is counted as late (they come every 10 ms).
private const val LATE_CHUNK_MS = 30L

private const val FULL_SCALE_SQUARED = 32768.0 * 32768.0

/**
 * Whether a live broadcast has the exterior speaker's route: the assistant's own tones and replies that share the route's stream (the
 * headrest speaker's) wait for it to be over, or they would be heard outside the car.
 */
internal object LiveBroadcastRoute {
    @Volatile private var busyUntilMs = 0L

    fun busy(): Boolean = SystemClock.elapsedRealtime() < busyUntilMs

    fun holdUntil(untilMs: Long) {
        busyUntilMs = untilMs
    }
}

/**
 * A live broadcast through the car's exterior speaker: what the cabin microphone hears is played outside, as it is said, until it is
 * stopped. It is the exterior speaker used the other way round from the assistant's reply: here the person in the car is the speaker.
 *
 * The audio goes cabin microphone ([BroadcastCapture], the voice-processed source) -> [TalkChain] (echo cancellation, filters, fade) -> a
 * track on the exterior speaker ([ExteriorSpeaker.newLiveTrack]). The car's route is opened and closed through [ExteriorSpeaker], the same way
 * for the assistant's announcements, so its safeguards hold here too: the helper that holds the switch puts it off if the heartbeat stops or the
 * link drops, and a hard time limit applies.
 *
 * What can end it: the user ([stop], the wheel's microphone button again), the time limit ([MAX_LIVE_MS]), a squeal the chain could
 * not stop ([FeedbackGuard]), the route being lost, the microphone failing, or a setting being switched off meanwhile. Whatever the reason,
 * the voice is cut at once, the route is held for the little that is still on its way out of the car and then put back, the music goes on, and
 * the closing tone comes after that.
 *
 * It starts at once: nothing is said or played first, and the route is switched as soon as the helper that holds it is there (it is kept
 * ready while the broadcast is armed). It does not decide *when* it may start (a conversation in progress, the setting): that is the caller's
 * ([start] only refuses what it cannot do). The closing tone it plays is the assistant's [Earcons].
 *
 * **The music** ([MusicMode], changed with [setMusicMode], also while it is live). The car has two ways out: the fast route sends the whole music
 * stream outside along with the voice, the slow navigation route sends only the navigation stream and leaves the music in the cabin. So the
 * three choices are three arrangements: *paused* (the fast route, the music stopped first), *outside* (the fast route, the music going out too)
 * and *inside* (the slow route). Switching between the fast route and the slow one while live closes one route and opens the other (about a
 * second, in which nothing is sent); the microphone and the chain go on meanwhile.
 *
 * The music is stopped *first*: the media sessions that are playing are asked to pause (the car's own media center ignores audio focus, which is
 * also asked for, so that nothing starts it again) and the route is switched only once they have said so and what they had already handed to the
 * car's audio path has run out, so that not a moment of the music is heard outside. At the end the route is put back first and the music goes on
 * after that.
 *
 * **The loudness** ([setLoudness]): see [BroadcastLoudness].
 */
@Singleton
class LiveBroadcast @Inject constructor(
    @ApplicationContext private val context: Context,
    exteriorSpeaker: Optional<ExteriorSpeaker>,
    private val preferences: PreferencesRepository,
    private val earcons: Earcons,
    private val mediaSources: MediaSources,
) {
    enum class State { IDLE, STARTING, LIVE, STOPPING }

    enum class StopReason(val failed: Boolean) {
        USER(false), TIME_LIMIT(false), SETTINGS_OFF(false),
        FEEDBACK(true), ROUTE_LOST(true), MIC_LOST(true), ROUTE_FAILED(true), MIC_FAILED(true),
    }

    enum class Refusal { NOT_ON_THIS_CAR, NO_MIC_PERMISSION, BUSY }

    sealed interface Event {
        /**
         * The microphone is going out (the sound itself follows once the route has settled). [musicChoice] is whether there is a choice of where
         * the music goes (the car has the fast route as well as the slow one), which is when it is worth offering it.
         */
        data class Live(val musicChoice: Boolean) : Event

        /** It is being ended, for [reason]: the voice is already cut, and what is left is putting the car back. The screen can drop what it shows. */
        data class Ending(val reason: StopReason) : Event

        /** It is over, and the car is back as it was (apart from the closing tone, which follows). */
        data class Stopped(val reason: StopReason) : Event
    }

    /**
     * What the last 100 ms were like, in dB below full scale (mean power, -90 for nothing): what the microphone heard, what was sent out, and the
     * echo of the exterior speaker that the canceller took out of it (the feedback). [fading] is whether the tail fade is holding the voice down;
     * [squeal] whether a squeal is being held back.
     */
    data class LevelFrame(val micDb: Float, val sentDb: Float, val echoDb: Float, val fading: Boolean, val squeal: Boolean)

    private val speaker: ExteriorSpeaker? = exteriorSpeaker.orElse(null)

    /**
     * Whether the audio mode is put on "in communication" while broadcasting (see [run]), as the exterior sound apps do for a live shout. It is
     * put back at the end.
     */
    @Volatile internal var communicationMode = true

    /** Whether the media route (the music stream outside: about 0.25 s round trip against 0.7 s for the navigation stream's) is used where the car offers it. */
    @Volatile internal var useMediaRoute = true

    /**
     * The echo canceller ([EchoCanceller]) and how it is set: whether it is on, how hard it suppresses (0..4, WebRTC's routing modes: 1 is the
     * earpiece's), how long after a chunk is played its sound reaches the microphone (the start-up hint, which it refines: 150 ms on the media route,
     * [navEchoDelayMs] on the navigation route, whose way round is several times as long), and how many chunks back the reference is taken. The
     * numbers are those of the exterior sound apps; a debug build can try others, a release build never does.
     */
    @Volatile internal var echoCancel = true
    @Volatile internal var echoMode = 1
    @Volatile internal var echoDelayMs = 150
    @Volatile internal var navEchoDelayMs = 400
    @Volatile internal var echoReferenceChunks = 2

    /** How much sound waits in the track before it starts (see [ExteriorSpeaker.newLiveTrack]); 0 = the platform's choice (all of its buffer). A debug build can try others. */
    @Volatile internal var trackStartMs = TRACK_START_MS

    /** A gain on the voice besides the loudness level's (1 = none; at most [TalkChain.MAX_GAIN] in all) and whether the unit's own gain control is left on the microphone. A debug build can try others. */
    @Volatile internal var voiceGain = 1.0f
    @Volatile internal var microphoneGainControlOff = false

    /** Whether two microphone channels are asked for first (and averaged). */
    @Volatile internal var stereoCapture = true

    /** How long after the route is switched the first sound waits (see [ExteriorSpeaker.routeSettleMs]); a negative number leaves it to the speaker. A debug build can try others. */
    @Volatile internal var settleMs = -1L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events

    private val _levels = MutableSharedFlow<LevelFrame>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A frame of levels every 100 ms while the microphone is on, for the level meter. */
    val levels: SharedFlow<LevelFrame> = _levels

    private val _musicMode = MutableStateFlow(MusicMode.DEFAULT)

    /** The music choice (see [MusicMode]): what the next broadcast does, and what a live one is switched to. */
    val musicMode: StateFlow<MusicMode> = _musicMode

    private val _musicShown = MutableStateFlow(MusicMode.DEFAULT)

    /** The music arrangement in effect, which is the choice except when the fast route would not take and the broadcast went out over the slow one (then it is [MusicMode.INSIDE]). */
    val musicShown: StateFlow<MusicMode> = _musicShown

    private val _loudness = MutableStateFlow(BroadcastLoudness.DEFAULT)

    /** How loud the voice is made outside (see [BroadcastLoudness]). */
    val loudness: StateFlow<ExteriorVolume> = _loudness

    private var session: Session? = null

    /** Whether this build and car can broadcast at all (the exterior speaker is there). */
    val isAvailable: Boolean get() = speaker != null

    /** Whether there is a choice of where the music goes: the car has the fast route as well as the slow one. */
    val hasMusicChoice: Boolean get() = useMediaRoute && speaker?.hasMediaRoute == true

    /** Starting, live or still putting the car back: nothing else may use the exterior speaker meanwhile. */
    val isActive: Boolean get() = _state.value != State.IDLE

    /** Starting or live: a press of the button means stop. */
    val isBroadcasting: Boolean get() = _state.value == State.STARTING || _state.value == State.LIVE

    /** Gets the exterior speaker's helper ready in the background (nothing is switched), so that [start] can switch at once. */
    fun prepare() {
        speaker?.prepare()
    }

    /** Puts back a route that an earlier run left on (see [ExteriorSpeaker.recoverLeftRoute]); nothing happens when none was. */
    fun recoverLeftRoute() {
        speaker?.recoverLeftRoute()
    }

    /**
     * Starts a broadcast; null when it is under way, or why it cannot be. Returns at once: [events] says when it is live.
     */
    fun start(): Refusal? {
        val speaker = speaker ?: return Refusal.NOT_ON_THIS_CAR
        if (!context.hasMicPermission()) return Refusal.NO_MIC_PERMISSION
        val started = synchronized(lock) {
            if (_state.value != State.IDLE) return Refusal.BUSY
            _state.value = State.STARTING
            Session(speaker).also { session = it }
        }
        scope.launch { run(started) }
        return null
    }

    /** Ends the broadcast, or the start of one, for [reason]. The first reason given is the one that counts. */
    fun stop(reason: StopReason = StopReason.USER) {
        val current = synchronized(lock) { session } ?: return
        if (current.stop.complete(reason)) AppLogger.log(TAG, "Stop asked for: $reason")
    }

    /**
     * Decides where the music goes: [MusicMode.PAUSED], [MusicMode.OUTSIDE] or [MusicMode.INSIDE]. It takes effect at once, in the broadcast that
     * is going on as well as in the next ones (a change between the fast route and the slow one takes about a second).
     */
    fun setMusicMode(mode: MusicMode) {
        if (_musicMode.value == mode) return
        _musicMode.value = mode
        AppLogger.log(TAG, "Music: ${mode.word}")
        val current = synchronized(lock) { session }
        if (current == null) {
            _musicShown.value = mode
            return
        }
        if (_state.value == State.LIVE) current.changes.trySend(mode)
    }

    /** Decides how loud the voice is made outside. It takes effect at once, in the broadcast that is going on as well as in the next ones. */
    fun setLoudness(level: ExteriorVolume) {
        if (_loudness.value == level) return
        _loudness.value = level
        AppLogger.log(TAG, "Loudness: ${level.word}")
        val current = synchronized(lock) { session } ?: return
        applyLoudness(current)
    }

    private class Session(val speaker: ExteriorSpeaker) {
        val stop = CompletableDeferred<StopReason>()
        val writeLock = Any()
        var released = false

        /** The route has settled: what the chain makes is written to the track (before that it is only run through the chain, so that it is warm). */
        @Volatile var sending = false

        /** The voice was cut (at the end, or while the route is being changed): nothing more is written until there is a new track. */
        var cut = false

        /** The track has been started (it is, with the first sound written to it, not before: a track that plays on nothing for a while runs dry when the sound comes). */
        var trackStarted = false
        var track: AudioTrack? = null
        var boost: LoudnessEnhancer? = null
        var capture: BroadcastCapture? = null
        var tap: BroadcastTap? = null
        @Volatile var echo: EchoCanceller? = null
        @Volatile var chain: TalkChain? = null
        val guard = FeedbackGuard()
        var focus: AudioFocusRequest? = null

        /** The players that were asked to pause through their sessions (and are asked to play at the end). */
        @Volatile var pausedSessions: Set<String> = emptySet()

        /** When the sessions in [pausedSessions] were asked to pause: one is not certain to have paused until MUSIC_STOP_WAIT_MS after. */
        @Volatile var pausedAtMs = 0L

        /** The route in use: the fast one (the music stream), or not (the navigation stream's). */
        @Volatile var media = false

        /** Whether the car's switch is on. */
        @Volatile var routeOn = false

        /** The music arrangement in effect. */
        @Volatile var mode = MusicMode.DEFAULT

        /** What the squeal guard has taken off the voice (1 = nothing). */
        @Volatile var guardFactor = 1.0f

        /** Choices of music arrangement made while it was live, to be acted on in turn (the last one is what counts). */
        val changes = Channel<MusicMode>(Channel.CONFLATED)
        var framesWritten = 0L
        var modeChanged = false
        var savedMode = AudioManager.MODE_NORMAL
        var lastFeedbackAtMs = 0L
        var chunks = 0

        // How the microphone's chunks come: the time the last one came, the longest wait for one, and how many waits were over 30 ms, since the last line of levels.
        @Volatile var lastChunkAtNs = 0L
        var longestWaitMs = 0L
        var lateChunks = 0
        var trackLogAtChunk = 0
        var startMs = 0
        var underruns = 0
        var musicMs = 0L
        var routeMs = 0L

        // The sums of one frame of levels (mean squares in 16-bit units) and the chunks in it.
        var meterMic = 0.0
        var meterSent = 0.0
        var meterEcho = 0.0
        var meterSqueal = false
        var meterChunks = 0
    }

    private suspend fun run(s: Session) {
        var reason = StopReason.USER
        try {
            val began = SystemClock.elapsedRealtime()
            AppLogger.log(TAG, "Starting")
            // The tone that says it is on, at once and in the cabin only (see [Earcons.playLive]): the route is not open yet, and the microphone's first
            // 300 ms are not sent, which is longer than the tone.
            earcons.playLive(onNavigationStream = useMediaRoute && s.speaker.hasMediaRoute && _musicMode.value != MusicMode.INSIDE)
            // The helper takes a moment to start when nothing has kept it ready: it starts now, while the music is stopped, and the route waits
            // for it (see [openRoute]).
            val helperReady = s.speaker.prepare()
            // A press in this moment cancels it before anything has been switched.
            if (s.stop.isCompleted) {
                reason = s.stop.await()
                return
            }
            // The route the music choice asks for, with the music stopped first where it is to be.
            val modeAtStart = _musicMode.value
            if (!openRoute(s, modeAtStart, helperReady)) {
                reason = if (s.stop.isCompleted) s.stop.await() else StopReason.ROUTE_FAILED
                return
            }
            val routeAt = SystemClock.elapsedRealtime()
            if (s.stop.isCompleted) {
                reason = s.stop.await()
                return
            }

            // What is local is got ready while the route settles (it takes a good part of a third of a second): the audio mode the way the
            // exterior sound apps have it for a live shout (put back at the end), the track, the echo canceller and the microphone. Until the
            // route has settled the microphone is only run through the chain, so that it is warm when the sound starts.
            if (communicationMode) {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                s.savedMode = audio.mode
                if (s.savedMode != AudioManager.MODE_IN_COMMUNICATION) {
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    s.modeChanged = true
                }
            }
            if (com.bydassistantng.BuildConfig.DEBUG) s.tap = BroadcastTap(context)
            setupTrack(s)
            setupChain(s)
            val capture = BroadcastCapture(agcOff = microphoneGainControlOff, stereo = stereoCapture)
            capture.onLost = { stop(StopReason.MIC_LOST) }
            s.capture = capture
            if (!capture.start { samples, _ -> onChunk(s, samples) }) {
                reason = StopReason.MIC_FAILED
                return
            }
            _state.value = State.LIVE
            _events.tryEmit(Event.Live(musicChoice = hasMusicChoice))
            // A choice made while it was starting could not be acted on then.
            if (_musicMode.value != modeAtStart) s.changes.trySend(_musicMode.value)

            // The rest of the settling, if there is any left, is waited out; a press during it ends the broadcast at once.
            val settle = if (settleMs >= 0) settleMs else s.speaker.routeSettleMs
            val left = settle - (SystemClock.elapsedRealtime() - routeAt)
            if (left > 0) {
                val early = withTimeoutOrNull(left) { s.stop.await() }
                if (early != null) {
                    reason = early
                    return
                }
            }
            settleTrack(s)
            s.sending = true
            val now = SystemClock.elapsedRealtime()
            AppLogger.log(
                TAG,
                "Live over the ${if (s.media) "media (music stream)" else "navigation stream"} route, ${capture.sourceName}, ${capture.channels} channel(s), " +
                    "communication mode ${s.modeChanged || s.savedMode == AudioManager.MODE_IN_COMMUNICATION}, music ${s.mode.word}, loudness ${_loudness.value.word}; " +
                    "sound goes out ${now - began} ms after the start (music ${s.musicMs} ms, route ${s.routeMs} ms, settling $settle ms), the track waits for ${s.startMs} ms of sound",
            )

            coroutineScope {
                val limit = launch {
                    delay(MAX_LIVE_MS)
                    stop(StopReason.TIME_LIMIT)
                }
                // Switching any of what it needs off while it is going ends it: the exterior speaker, the broadcast, vehicle control.
                val watch = launch {
                    combine(preferences.exteriorSpeakerEnabled, preferences.liveBroadcastEnabled, preferences.vehicleControlEnabled) { a, b, c -> a && b && c }
                        .first { !it }
                    stop(StopReason.SETTINGS_OFF)
                }
                // A microphone that stops giving sound without saying so (the audio server restarted under it, say) would leave the route open
                // and the broadcast "live" and silent until the time limit: no chunk for a couple of seconds ends it.
                val micWatch = launch {
                    while (true) {
                        delay(MIC_WATCH_MS)
                        val last = s.lastChunkAtNs
                        if (last != 0L && (SystemClock.elapsedRealtimeNanos() - last) / 1_000_000L > MIC_SILENT_MS) {
                            AppLogger.log(TAG, "No sound from the microphone for $MIC_SILENT_MS ms — it is lost")
                            stop(StopReason.MIC_LOST)
                            break
                        }
                    }
                }
                // Changes of where the music goes, made while it is live, one at a time.
                val changer = launch {
                    for (mode in s.changes) {
                        if (s.stop.isCompleted) break
                        changeMusic(s, mode)
                    }
                }
                reason = s.stop.await()
                limit.cancel()
                watch.cancel()
                micWatch.cancel()
                changer.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.logError(TAG, "The broadcast failed", e)
            reason = if (s.routeOn) StopReason.MIC_FAILED else StopReason.ROUTE_FAILED
        } finally {
            withContext(NonCancellable) { teardown(s, reason) }
        }
    }

    /**
     * Gets the route for [mode] open, with the music stopped first when it is to be. The fast route first, where the car has it and the choice is not
     * [MusicMode.INSIDE]; the navigation stream's route when that is the choice or the fast one does not take. False when no route would open, or
     * when a press ended it meanwhile (the caller tells which by [Session.stop]). On the fast route the music would go outside with the voice, so
     * it is stopped (unless it is to go out) and the route is switched only once it has.
     *
     * [helperReady] is the helper that holds the switch getting ready (null when it is certainly there, as when the route is changed while live). It
     * is looked at after the music has stopped, which the helper's start overlaps, so that a start with a helper at hand takes as long as it ever
     * did; but a helper that is slow to come (ADB not allowed yet: the car's prompt can be left up for a minute) is a wait a press can end, with the
     * music let go on again. Nothing has been switched until it is over.
     */
    private suspend fun openRoute(s: Session, mode: MusicMode, helperReady: Deferred<Boolean>? = null): Boolean {
        val began = SystemClock.elapsedRealtime()
        val tryMedia = useMediaRoute && s.speaker.hasMediaRoute && mode != MusicMode.INSIDE
        if (tryMedia && mode == MusicMode.PAUSED) {
            takeMusicFocus(s)
            stopMusic(s)
            if (s.stop.isCompleted) return false
        }
        val musicDone = SystemClock.elapsedRealtime()
        if (helperReady != null && !s.speaker.isReady && awaitHelperOrStop(helperReady, s.stop) == null) {
            giveBackMusic(s)
            return false
        }
        val media = tryMedia && s.speaker.openMedia(settle = false)
        if (tryMedia && !media) giveBackMusic(s)
        if (!media && !s.speaker.open(settle = false)) return false
        s.media = media
        s.routeOn = true
        s.mode = if (media) mode else MusicMode.INSIDE
        s.musicMs = musicDone - began
        s.routeMs = SystemClock.elapsedRealtime() - musicDone
        _musicShown.value = s.mode
        LiveBroadcastRoute.holdUntil(Long.MAX_VALUE)
        s.speaker.setRouteLostListener { stop(StopReason.ROUTE_LOST) }
        return true
    }

    /** A new track for the route in use (not started: that is done with the first sound written to it), with the loudness boost where the route has one. */
    private fun setupTrack(s: Session) {
        s.startMs = if (s.media) trackStartMs else max(trackStartMs, NAV_TRACK_START_MS)
        val track = s.speaker.newLiveTrack(
            BROADCAST_SAMPLE_RATE,
            if (s.media) MusicAudio.attributes() else NaviAudio.attributes(),
            lowLatency = false,
            bufferFactor = 1,
            startThresholdMs = s.startMs,
            minBufferBytes = TRACK_MIN_BUFFER_BYTES,
        )
        s.boost = if (s.media) null else attachBoost(track)
        synchronized(s.writeLock) {
            s.track = track
            s.trackStarted = false
            s.framesWritten = 0
            s.cut = false
            s.trackLogAtChunk = s.chunks + LATENCY_LOG_CHUNKS
        }
    }

    /** A loudness boost on the track for the navigation route, the way the assistant's replies on it have one. */
    private fun attachBoost(track: AudioTrack): LoudnessEnhancer? = try {
        LoudnessEnhancer(track.audioSessionId).apply {
            setTargetGain(BroadcastLoudness.boostMb(_loudness.value))
            enabled = true
        }
    } catch (e: Exception) {
        AppLogger.logError(TAG, "Could not boost the exterior track", e)
        null
    }

    /** A new echo canceller and chain for the route in use (what one learned of the other route's echo is no use on this one). */
    private fun setupChain(s: Session) {
        val before = s.echo
        val echo = if (echoCancel) EchoCanceller.create(BROADCAST_SAMPLE_RATE, echoMode) else null
        val delayMs = if (s.media) echoDelayMs else navEchoDelayMs
        AppLogger.log(
            TAG,
            if (echo != null) "Echo canceller on: mode $echoMode, $delayMs ms, reference $echoReferenceChunks chunks back" else "No echo canceller (off, or none on this device)",
        )
        val chain = TalkChain(echo, TalkChain.Settings(delayMs = delayMs, referenceChunks = echoReferenceChunks))
        s.echo = echo
        s.chain = chain
        applyLoudness(s)
        // The capture thread may be in the old canceller still: closing it is safe, a call after it does nothing.
        before?.close()
    }

    /** The voice's gain and the track's boost for the loudness level and the route in use. */
    private fun applyLoudness(s: Session) {
        val chain = s.chain ?: return
        val level = _loudness.value
        val base = if (s.media) BroadcastLoudness.mediaGain(level) else 1.0f
        chain.gain = min(TalkChain.MAX_GAIN, base * voiceGain * s.guardFactor)
        s.boost?.let { runCatching { it.setTargetGain(BroadcastLoudness.boostMb(level)) } }
    }

    /** One chunk of the microphone's 16-bit PCM (10 ms), on the capture thread. */
    private fun onChunk(s: Session, samples: ShortArray) {
        if (s.stop.isCompleted) return
        val chain = s.chain ?: return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (s.lastChunkAtNs != 0L) {
            val waitMs = (nowNs - s.lastChunkAtNs) / 1_000_000L
            if (waitMs > s.longestWaitMs) s.longestWaitMs = waitMs
            if (waitMs > LATE_CHUNK_MS) s.lateChunks++
        }
        s.lastChunkAtNs = nowNs
        val heard = s.guard.observe(samples)
        if (heard.feedback) onFeedback(s)
        val played = chain.process(samples, silent = heard.muted || !s.sending)
        s.tap?.add(samples, chain.lastCleaned, played)

        s.chunks++
        if (s.chunks % MUSIC_CHECK_CHUNKS == 0) chain.musicBackground = s.mode == MusicMode.INSIDE && mediaSources.anyPlaying()
        accumulateLevels(s, chain, heard.muted)
        if (s.chunks % LEVEL_LOG_CHUNKS == 0) {
            val stats = chain.takeStats()
            AppLogger.log(
                TAG,
                "Levels over the last ${LEVEL_LOG_CHUNKS / 100} s: microphone ${"%.0f".format(stats.microphoneDb)} dB, after the echo canceller ${"%.0f".format(stats.cleanedDb)} dB, " +
                    "sent ${"%.0f".format(stats.sentDb)} dB, tail fade down to ${"%.2f".format(stats.lowestTailGain)}, voice level followed ${"%.0f".format(stats.speechRms)}; " +
                    "the microphone's chunks: longest wait ${s.longestWaitMs} ms, ${s.lateChunks} later than $LATE_CHUNK_MS ms",
            )
            s.longestWaitMs = 0
            s.lateChunks = 0
        }

        // Until the route has settled what is written is silence (the chain makes silence of it): the track is running on it by the time the voice comes,
        // and whatever the start of a track does to it (it ran dry a good many times right after a route was changed) is over by then.
        synchronized(s.writeLock) {
            val track = s.track
            if (s.released || s.cut || track == null) return
            // The track starts with its first sound: one that has played on nothing for a while runs dry when the sound comes.
            if (!s.trackStarted) {
                s.trackStarted = true
                if (!s.media) {
                    val silence = ShortArray(BROADCAST_SAMPLE_RATE * NAV_PREFILL_MS / 1000)
                    val queued = track.write(silence, 0, silence.size)
                    if (queued > 0) s.framesWritten += queued
                }
                runCatching { track.play() }
            }
            var offset = 0
            while (offset < played.size) {
                val written = track.write(played, offset, played.size - offset)
                if (written <= 0) {
                    AppLogger.log(TAG, "The track would not take more ($written)")
                    stop(StopReason.ROUTE_LOST)
                    return
                }
                offset += written
            }
            s.framesWritten += played.size
            if (s.chunks == s.trackLogAtChunk) logTrack(s, track)
            if (s.chunks % START_CHECK_CHUNKS == 0) adaptStart(s, track)
        }
    }

    /**
     * Called as the sound is about to go on: if the track ran dry while it was warming up on silence it is started over, still on silence, so that the voice
     * does not begin in that state.
     */
    private fun settleTrack(s: Session) {
        synchronized(s.writeLock) {
            val track = s.track ?: return
            if (!s.trackStarted) return
            val underruns = track.underrunCount
            if (underruns > s.underruns) {
                AppLogger.log(TAG, "The track ran dry $underruns time(s) while it warmed up on silence: it is started over (the microphone's longest wait ${s.longestWaitMs} ms)")
                s.underruns = underruns
                runCatching { track.pause() }
                runCatching { track.flush() }
                s.trackStarted = false
            }
        }
    }

    /** Adds this chunk to the frame of levels the meter gets every 100 ms. */
    private fun accumulateLevels(s: Session, chain: TalkChain, squealing: Boolean) {
        s.meterMic += chain.lastMicRms * chain.lastMicRms
        s.meterSent += chain.lastSentRms * chain.lastSentRms
        s.meterEcho += chain.lastEchoRms * chain.lastEchoRms
        if (squealing) s.meterSqueal = true
        if (++s.meterChunks < LEVEL_FRAME_CHUNKS) return
        val n = s.meterChunks.toDouble()
        _levels.tryEmit(LevelFrame(toDb(s.meterMic / n), toDb(s.meterSent / n), toDb(s.meterEcho / n), chain.fading, s.meterSqueal))
        s.meterMic = 0.0
        s.meterSent = 0.0
        s.meterEcho = 0.0
        s.meterSqueal = false
        s.meterChunks = 0
    }

    private fun toDb(meanSquares: Double): Float = if (meanSquares < 1.0) -90f else (10.0 * log10(meanSquares / FULL_SCALE_SQUARED)).toFloat()

    /** How the track is buffered, as the platform made it, and how much of what was written is still waiting to be played. */
    private fun logTrack(s: Session, track: AudioTrack) {
        val timestamp = AudioTimestamp()
        val queuedMs = if (track.getTimestamp(timestamp)) {
            val played = timestamp.framePosition + (System.nanoTime() - timestamp.nanoTime) * BROADCAST_SAMPLE_RATE / 1_000_000_000L
            (s.framesWritten - played) * 1000 / BROADCAST_SAMPLE_RATE
        } else {
            -1L
        }
        val performanceMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) track.performanceMode else -1
        AppLogger.log(
            TAG,
            "Track: buffer ${track.bufferSizeInFrames} frames (${track.bufferSizeInFrames * 1000 / BROADCAST_SAMPLE_RATE} ms), performance mode $performanceMode, " +
                "about $queuedMs ms written and not yet played, underruns ${track.underrunCount}",
        )
    }

    /**
     * A track that has run dry (the microphone's chunks came late) waits for a little more sound before it plays, each time: a gap now and then is
     * worse than a few milliseconds more of delay. Only where the platform lets the threshold be set (Android 12 and later) and it was asked for.
     */
    private fun adaptStart(s: Session, track: AudioTrack) {
        val underruns = track.underrunCount
        if (underruns <= s.underruns) return
        s.underruns = underruns
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || s.startMs <= 0 || s.startMs >= TRACK_START_MAX_MS) return
        s.startMs = min(TRACK_START_MAX_MS, s.startMs + TRACK_START_STEP_MS)
        runCatching { track.setStartThresholdInFrames(max(1, BROADCAST_SAMPLE_RATE * s.startMs / 1000)) }
        AppLogger.log(TAG, "The track ran dry (underruns $underruns): it now waits for ${s.startMs} ms of sound before it plays (the microphone's longest wait lately ${s.longestWaitMs} ms)")
    }

    /** The microphone is hearing the exterior speaker: the sound is already held back; this lowers the gain, and a repeat ends it. */
    private fun onFeedback(s: Session) {
        val now = SystemClock.elapsedRealtime()
        if (s.guardFactor * SQUEAL_GAIN_FACTOR < MIN_GUARD_FACTOR || now - s.lastFeedbackAtMs < FEEDBACK_REPEAT_WINDOW_MS) {
            AppLogger.log(TAG, "Squeal again — ending the broadcast")
            stop(StopReason.FEEDBACK)
            return
        }
        s.lastFeedbackAtMs = now
        s.guardFactor *= SQUEAL_GAIN_FACTOR
        applyLoudness(s)
        AppLogger.log(TAG, "Squeal — the sound is held back and the voice is lowered by ${"%.0f".format(-20.0 * log10(s.guardFactor.toDouble()))} dB")
    }

    // ── The music ─────────────────────────────────────────────────────────────────────────────

    /** Asks for the music stream's audio focus for a moment, so that nothing starts the music again meanwhile; kept until [giveBackMusic]. */
    private fun takeMusicFocus(s: Session) {
        if (s.focus != null) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { }
            .build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        AppLogger.log(TAG, "Music focus asked for: ${if (granted) "granted" else "not granted"}")
        if (granted) s.focus = request
    }

    /**
     * Stops the music and waits until it has stopped. The media sessions that are playing are asked to pause at once (the car's own media center
     * ignores audio focus; one that does heed it is paused all the same, and goes on at the end), and what is waited for is their saying so (a
     * player reports a pause through its session at once; see [isMusicPlayerStarted] for why not the player list), or, when no session is playing, the
     * list of players on the music stream. Then the route waits a little more for what the player had already handed to the car's audio path.
     * Returns when it is quiet, or when it is clear that it is not going to be; a press ends the wait.
     */
    private suspend fun stopMusic(s: Session) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sessions = mediaSources.anyPlaying()
        // The broadcast's own track on the music stream, if it is playing now, does not count.
        val own = synchronized(s.writeLock) { if (s.media && s.track != null && s.trackStarted && !s.cut) 1 else 0 }
        val players = isMusicPlayerStarted(audio, own)
        if (!sessions && !players) {
            AppLogger.log(TAG, "No music is playing")
            return
        }
        val began = SystemClock.elapsedRealtime()
        if (sessions) {
            s.pausedSessions = mediaSources.pausePlaying()
            s.pausedAtMs = SystemClock.elapsedRealtime()
        }

        fun playing() = if (sessions) mediaSources.anyPlaying() else isMusicPlayerStarted(audio, own)

        val until = began + MUSIC_STOP_WAIT_MS
        while (SystemClock.elapsedRealtime() < until && !s.stop.isCompleted && playing()) delay(MUSIC_POLL_MS)
        val quiet = !playing()
        val stoppedAfter = SystemClock.elapsedRealtime() - began
        if (quiet && !s.stop.isCompleted) delay(MUSIC_DRAIN_MS)
        AppLogger.log(
            TAG,
            if (quiet) "Music stopped after $stoppedAfter ms (${if (sessions) "its session" else "the player list"}), the route waits $MUSIC_DRAIN_MS ms more for what was on its way"
            else "Music still playing after $stoppedAfter ms — going on with it",
        )
    }

    /**
     * Whether a player is started on the music stream right now, from the list the system keeps of them (the apps' own reports), not counting speech,
     * navigation prompts and the like. A player of a plain AudioTrack leaves it the moment it pauses; an AAudio player (Spotify's) some seconds later.
     * The system's own "is music active" is not used: on this car it reads true with nothing playing.
     */
    private fun isMusicPlayerStarted(audio: AudioManager, own: Int = 0): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val players = audio.activePlaybackConfigurations.count { configuration ->
            val attributes = configuration.audioAttributes
            val onTheMusicStream = attributes.usage == AudioAttributes.USAGE_MEDIA || attributes.usage == AudioAttributes.USAGE_GAME ||
                attributes.usage == AudioAttributes.USAGE_UNKNOWN
            val notMusic = attributes.contentType == AudioAttributes.CONTENT_TYPE_SPEECH || attributes.contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION ||
                attributes.contentType == NaviAudio.CONTENT_TYPE_NAVI
            onTheMusicStream && !notMusic
        }
        // The broadcast's own track on the music stream (the fast route) is one of them while it plays: it is not the music.
        return players > own
    }

    /**
     * Debug builds: stops the music the way a broadcast would (the focus request, the pause, the wait for the player) and lets it go on again a moment
     * later, with no route and no sound outside; the music stops in the cabin for that moment. Logs how long the player took.
     */
    internal suspend fun debugMusicWait() {
        val speaker = speaker ?: return
        val s = Session(speaker)
        takeMusicFocus(s)
        stopMusic(s)
        delay(1_500)
        giveBackMusic(s)
    }

    /** Lets the music go on: the focus is given up, and the players that were asked to pause through their sessions are asked to play. */
    private suspend fun giveBackMusic(s: Session) {
        val request = s.focus
        s.focus = null
        if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(request) }
            AppLogger.log(TAG, "Music focus given back")
        }
        val paused = s.pausedSessions
        s.pausedSessions = emptySet()
        if (paused.isEmpty()) return
        // The car's media centre reports a pause up to half a second after it was asked for. A press that ends the broadcast inside that time finds the
        // session still saying it plays, and "play" to it would be followed by the pause: the music would stay off. So a session still playing is waited
        // for, until the time a pause is given, and only then asked to play.
        val until = s.pausedAtMs + MUSIC_STOP_WAIT_MS
        while (SystemClock.elapsedRealtime() < until && mediaSources.stillPlaying(paused)) delay(MUSIC_POLL_MS)
        mediaSources.resume(paused)
    }

    // ── Changing the music arrangement while live ─────────────────────────────────────────────

    /**
     * Makes the live broadcast what [mode] says. Between the fast route's two arrangements only the music changes: stopped (the route is open already,
     * so the little the player still has on its way is heard outside, which cannot be helped) or let go on. Between the fast route and the slow one the
     * route is changed: the voice is cut and drained, the route put back, the music dealt with, the other route opened, a track and a chain made for
     * it, and the sound goes on once it has settled. The microphone and the chain's learning of the voice go on meanwhile; nothing is sent.
     */
    private suspend fun changeMusic(s: Session, mode: MusicMode) {
        val toMedia = useMediaRoute && s.speaker.hasMediaRoute && mode != MusicMode.INSIDE
        if (toMedia == s.media) {
            if (toMedia) {
                if (mode == MusicMode.PAUSED) {
                    takeMusicFocus(s)
                    stopMusic(s)
                } else {
                    giveBackMusic(s)
                }
            }
            s.mode = if (toMedia) mode else MusicMode.INSIDE
            _musicShown.value = s.mode
            return
        }
        AppLogger.log(TAG, "Changing the route: ${if (s.media) "media" else "navigation"} -> ${if (toMedia) "media" else "navigation"} for music ${mode.word}")
        _musicShown.value = mode
        val fast = s.media
        s.sending = false
        cutSound(s)
        delay(if (fast) LIVE_DRAIN_MS else OUTSIDE_RELEASE_GRACE_MS)
        releaseTrack(s)
        s.speaker.setRouteLostListener(null)
        s.speaker.close()
        s.routeOn = false
        delay(OUTSIDE_RELEASE_SWITCH_MS)
        // The music that was stopped goes on in the cabin once the fast route is put back, if the next arrangement is to have it inside; with the
        // fast route to come it is dealt with by openRoute.
        if (mode == MusicMode.INSIDE) giveBackMusic(s)
        if (s.stop.isCompleted) return
        if (!openRoute(s, mode)) {
            if (!s.stop.isCompleted) stop(StopReason.ROUTE_FAILED)
            return
        }
        val routeAt = SystemClock.elapsedRealtime()
        setupTrack(s)
        setupChain(s)
        val settle = if (settleMs >= 0) settleMs else s.speaker.routeSettleMs
        val left = settle - (SystemClock.elapsedRealtime() - routeAt)
        if (left > 0) withTimeoutOrNull(left) { s.stop.await() }
        if (s.stop.isCompleted) return
        settleTrack(s)
        s.sending = true
        AppLogger.log(TAG, "On the ${if (s.media) "media" else "navigation"} route, music ${s.mode.word}: the sound goes on")
    }

    // ── Ending ────────────────────────────────────────────────────────────────────────────────

    /** The voice stops now: what is waiting in the track is dropped, and nothing more is written. */
    private fun cutSound(s: Session) {
        synchronized(s.writeLock) {
            s.cut = true
            val track = s.track ?: return
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    /** Lets go of the track and its loudness boost. */
    private fun releaseTrack(s: Session) {
        val track: AudioTrack?
        synchronized(s.writeLock) {
            track = s.track
            s.track = null
            s.cut = true
        }
        runCatching { s.boost?.release() }
        s.boost = null
        runCatching { track?.release() }
    }

    private suspend fun teardown(s: Session, reason: StopReason) {
        _state.value = State.STOPPING
        val began = SystemClock.elapsedRealtime()
        AppLogger.log(TAG, "Ending: $reason")
        _events.tryEmit(Event.Ending(reason))
        s.sending = false
        val track = s.track
        // The voice stops at once (what is waiting in the track is dropped); what is already in the car's mixer and amplifier comes out in the next
        // moments, and the route is held for it: a third of a second on the fast route, half a second on the navigation route, whose way out is deeper.
        val fast = s.media
        cutSound(s)

        // What takes a moment is done while that sound drains out of the car.
        val work = scope.launch(Dispatchers.IO) {
            s.capture?.stop()
            s.echo?.close()
            s.tap?.close()
            if (track != null) AppLogger.log(TAG, "Underruns: ${track.underrunCount}")
            if (s.modeChanged) runCatching { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = s.savedMode }
        }
        if (track != null) delay(if (fast) LIVE_DRAIN_MS else OUTSIDE_RELEASE_GRACE_MS)
        work.join()
        synchronized(s.writeLock) { s.released = true }
        runCatching { s.boost?.release() }
        runCatching { track?.release() }
        if (s.routeOn) {
            LiveBroadcastRoute.holdUntil(SystemClock.elapsedRealtime() + OUTSIDE_RELEASE_SWITCH_MS)
            s.speaker.setRouteLostListener(null)
            s.speaker.close()
            delay(OUTSIDE_RELEASE_SWITCH_MS)
        }
        // The music may play again once the route is back: nothing of it was heard outside, and it goes on where it was.
        giveBackMusic(s)
        LiveBroadcastRoute.holdUntil(0L)
        synchronized(lock) { session = null }
        _musicShown.value = _musicMode.value
        _state.value = State.IDLE
        _events.tryEmit(Event.Stopped(reason))
        AppLogger.log(TAG, "Over, ${SystemClock.elapsedRealtime() - began} ms after it was ended")
        // The closing tone is made after the route is off, so that it is heard in the cabin only.
        earcons.play(if (reason.failed) Earcon.ERROR else Earcon.DONE)
    }
}

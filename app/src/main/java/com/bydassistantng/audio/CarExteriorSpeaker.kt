package com.bydassistantng.audio

import android.content.Context
import android.os.Build
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OutsideSpeaker"

// What the exterior sound apps wait after turning the switch on, before the first sound.
private const val SETTLE_MS = 300L
private const val HEARTBEAT_MS = 500L

// A helper nobody has used for this long is shut down; the next announcement starts a fresh one.
private const val IDLE_CLOSE_MS = 3 * 60_000L

/**
 * The [ExteriorSpeaker] of the car: the exterior speaker switch held by [OutsideSpeakerHelper] (which
 * switches itself off if the heartbeat stops) and a navi-stream track for the audio.
 *
 * The helper process is started ahead of time ([prepare]) and kept for a few minutes, so an announcement
 * only pays for the switch itself.
 */
@Singleton
internal class CarExteriorSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExteriorSpeaker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = Mutex()
    private val commands = Mutex()
    private var session: OutsideSpeakerSession? = null
    private var heartbeat: Job? = null
    private var idleJob: Job? = null
    private var savedVolume: Int? = null
    private var switchedOn = false

    // Whether a route may have been left on by a run that died (see [ExteriorRouteMemory]).
    private val memory = ExteriorRouteMemory(context.getSharedPreferences("exterior_route", Context.MODE_PRIVATE))

    // The helper command that switched the route on ("on" or "on media"): a second user can only share the same route.
    private var routeCommand: String? = null

    @Volatile private var routeLost: (() -> Unit)? = null

    // Announcements and effects can overlap: the switch goes off with the last of them.
    private var users = 0

    override val isReady: Boolean get() = session != null

    override val soundEffects: List<SoundEffect> get() = OutsideEffects.catalog

    override fun renderEffect(name: String): ShortArray? = OutsideEffects.render(name)

    override fun prepare(): Deferred<Boolean> = scope.async {
        try {
            state.withLock {
                val helper = ensureSession()
                // The helper is up: the moment to put back a route that an earlier run left on, if it did.
                if (helper != null) putBackLeftRoute(helper)
                armIdleClose()
                helper != null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Often nobody waits for this one: it must not take the app down.
            AppLogger.logError(TAG, "Could not get the helper ready", e)
            false
        }
    }

    override fun recoverLeftRoute() {
        if (!memory.possiblyOn) return
        scope.launch {
            try {
                state.withLock {
                    // Not while a route is in use (this run's own), and not when somebody else already put it back.
                    if (switchedOn || !memory.possiblyOn) return@withLock
                    val helper = ensureSession()
                    if (helper == null) {
                        AppLogger.log(TAG, "A route may be left on and there is no helper to switch it off (is ADB reachable?) — trying again later")
                        return@withLock
                    }
                    putBackLeftRoute(helper)
                    armIdleClose()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.logError(TAG, "Could not put back a route an earlier run left on", e)
            }
        }
    }

    /** With [state] held: has [helper] switch both routes off when the memory says one may be on. Forgets it once the car has taken that. */
    private suspend fun putBackLeftRoute(helper: OutsideSpeakerSession) {
        if (!memory.possiblyOn || switchedOn) return
        val reset = commands.withLock { helper.command("reset") }
        AppLogger.log(TAG, "A route that an earlier run may have left on: reset -> $reset")
        if (reset is VehicleDispatchResult.Success) {
            memory.clear()
        } else if (reset is VehicleDispatchResult.Failure && reset.error == VehicleDispatchError.HELPER_UNAVAILABLE) {
            dropSession()
        }
    }

    override val hasMediaRoute: Boolean get() = ExteriorSpeakerSwitch.isMediaRouteOnThisCar()

    override val routeSettleMs: Long get() = SETTLE_MS

    override suspend fun open(settle: Boolean): Boolean = openWith("on", settle)

    override suspend fun openMedia(settle: Boolean): Boolean = hasMediaRoute && openWith("on media", settle)

    private suspend fun openWith(command: String, settle: Boolean): Boolean = state.withLock {
        idleJob?.cancel()
        if (switchedOn) {
            // Already on for another announcement or effect, if it is the same route.
            if (routeCommand != command) {
                AppLogger.log(TAG, "Exterior speaker is on the other route; '$command' not made")
                return@withLock false
            }
            users++
            return@withLock true
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val helper = ensureSession() ?: run {
            AppLogger.log(TAG, "Exterior speaker: no helper (is ADB reachable?)")
            return@withLock false
        }
        try {
            // The exterior level does not follow the navi volume (measured), so the user's own setting is left
            // alone. Only a navi volume of 0 matters: the mixer would silence the track altogether.
            val navi = audio.getStreamVolume(NaviAudio.STREAM_NAVI)
            if (navi < 1) {
                savedVolume = navi
                audio.setStreamVolume(NaviAudio.STREAM_NAVI, 1, 0)
            }
            // From here the route may be on until the switch-off is seen to be taken: remembered before the helper is asked, because the app
            // may die the next moment and the helper with it.
            memory.markOn()
            val on = commands.withLock { helper.command(command) }
            AppLogger.log(TAG, "Exterior speaker $command (navi volume $navi): $on")
            if (on !is VehicleDispatchResult.Success) {
                if (on is VehicleDispatchResult.Failure && on.error == VehicleDispatchError.HELPER_UNAVAILABLE) {
                    // No answer: what the car was told is not known, so the memory stays and a fresh helper puts it back.
                    dropSession()
                    scope.launch { recoverLeftRoute() }
                } else {
                    // The helper answered that it did not switch on (and put back what it had written): nothing to remember.
                    memory.clear()
                }
                restoreVolume(audio)
                return@withLock false
            }
            switchedOn = true
            routeCommand = command
            users = 1
            heartbeat = scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    val answer = commands.withLock { helper.command("hold") }
                    if (answer !is VehicleDispatchResult.Success) {
                        AppLogger.log(TAG, "Exterior speaker heartbeat stopped: $answer")
                        routeLost?.invoke()
                        break
                    }
                }
            }
            // The first sound waits for the route to take effect; a caller with other things to do meanwhile waits it out itself.
            if (settle) delay(SETTLE_MS)
            true
        } catch (t: Throwable) {
            AppLogger.logError(TAG, "Could not switch to the exterior speaker", t)
            val off = runCatching { commands.withLock { helper.command("off") } }.getOrNull()
            if (off is VehicleDispatchResult.Success) memory.clear() else scope.launch { recoverLeftRoute() }
            restoreVolume(audio)
            switchedOn = false
            routeCommand = null
            users = 0
            false
        }
    }

    override fun newTrack(sampleRate: Int, bufferSizeBytes: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(NaviAudio.attributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

    override fun newLiveTrack(sampleRate: Int, attributes: AudioAttributes, lowLatency: Boolean, bufferFactor: Int, startThresholdMs: Int, minBufferBytes: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * bufferFactor, minBufferBytes, 640))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = builder.build()
        if (startThresholdMs > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { track.setStartThresholdInFrames(maxOf(1, sampleRate * startThresholdMs / 1000)) }
        }
        return track
    }

    override fun close() {
        scope.launch { closeNow() }
    }

    override fun setRouteLostListener(listener: (() -> Unit)?) {
        routeLost = listener
    }

    private suspend fun closeNow() = withContext(NonCancellable) {
        state.withLock {
            if (users > 0) users--
            if (users > 0) return@withLock // another announcement or effect is still using it
            heartbeat?.cancel()
            heartbeat = null
            val helper = session
            var putBack = true
            if (switchedOn && helper != null) {
                val off = commands.withLock { helper.command("off") }
                AppLogger.log(TAG, "Exterior speaker off: $off")
                putBack = off is VehicleDispatchResult.Success
            } else if (switchedOn) {
                putBack = false
            }
            if (putBack) memory.clear()
            switchedOn = false
            routeCommand = null
            restoreVolume(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            armIdleClose()
            // The switch-off was not seen to be taken (the helper is gone, or did not answer): a fresh one puts the route back.
            if (!putBack) scope.launch { recoverLeftRoute() }
        }
    }

    /** The helper process, started if it is not running. Called with [state] held. */
    private suspend fun ensureSession(): OutsideSpeakerSession? {
        session?.let { return it }
        return OutsideSpeakerSession.open(context).also { session = it }
    }

    private fun dropSession() {
        session?.close()
        session = null
    }

    private fun restoreVolume(audio: AudioManager) {
        savedVolume?.let { runCatching { audio.setStreamVolume(NaviAudio.STREAM_NAVI, it, 0) } }
        savedVolume = null
    }

    private fun armIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            state.withLock {
                if (!switchedOn) {
                    AppLogger.log(TAG, "Exterior speaker helper idle — shutting it down")
                    dropSession()
                }
            }
        }
    }
}

package com.bydassistantng.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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

    // Announcements and effects can overlap: the switch goes off with the last of them.
    private var users = 0

    override val isReady: Boolean get() = session != null

    override val soundEffects: List<SoundEffect> get() = OutsideEffects.catalog

    override fun renderEffect(name: String): ShortArray? = OutsideEffects.render(name)

    override fun prepare() {
        scope.launch {
            state.withLock {
                ensureSession()
                armIdleClose()
            }
        }
    }

    override suspend fun open(): Boolean = state.withLock {
        idleJob?.cancel()
        if (switchedOn) {
            // Already on for another announcement or effect.
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
            val on = commands.withLock { helper.command("on") }
            AppLogger.log(TAG, "Exterior speaker on (navi volume $navi): $on")
            if (on !is VehicleDispatchResult.Success) {
                if (on is VehicleDispatchResult.Failure && on.error == VehicleDispatchError.HELPER_UNAVAILABLE) dropSession()
                restoreVolume(audio)
                return@withLock false
            }
            switchedOn = true
            users = 1
            delay(SETTLE_MS)
            heartbeat = scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    val answer = commands.withLock { helper.command("hold") }
                    if (answer !is VehicleDispatchResult.Success) {
                        AppLogger.log(TAG, "Exterior speaker heartbeat stopped: $answer")
                        break
                    }
                }
            }
            true
        } catch (t: Throwable) {
            AppLogger.logError(TAG, "Could not switch to the exterior speaker", t)
            runCatching { commands.withLock { helper.command("off") } }
            restoreVolume(audio)
            switchedOn = false
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

    override fun close() {
        scope.launch { closeNow() }
    }

    private suspend fun closeNow() = withContext(NonCancellable) {
        state.withLock {
            if (users > 0) users--
            if (users > 0) return@withLock // another announcement or effect is still using it
            heartbeat?.cancel()
            heartbeat = null
            val helper = session
            if (switchedOn && helper != null) {
                val off = commands.withLock { helper.command("off") }
                AppLogger.log(TAG, "Exterior speaker off: $off")
            }
            switchedOn = false
            restoreVolume(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            armIdleClose()
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

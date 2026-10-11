package com.bydassistantng.debug

import com.bydassistantng.audio.NaviAudio
import com.bydassistantng.audio.OutsideEffects
import com.bydassistantng.audio.OutsideSpeakerSession
import com.bydassistantng.audio.OutsideTone
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.LoudnessEnhancer
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.VehicleDispatchResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "OutsideSpeaker"

/** What happens to the navi stream's volume while the sound plays. */
internal enum class StreamVolume { KEEP, ZERO, MAX }

/**
 * How one try is made. The defaults are the recipe the exterior sound apps share; the rest are the
 * places where they differ, so each can be tried on its own.
 */
internal data class OutsideTry(
    /** `chime` plays a tone, `switch` only holds the switch, `state` only reads, `off` only switches off. */
    val mode: String = "chime",
    val streamType: Int = 15,
    val usage: Int = AudioAttributes.USAGE_MEDIA,
    /** 6 is `CONTENT_TYPE_NAVI` on this car. */
    val contentType: Int = 6,
    /** 0x4800 is what the apps set; FLAG_NAVI_UE is 0x20000. */
    val flags: Int = 0x4800,
    val volume: StreamVolume = StreamVolume.KEEP,
    /** Loudness boost in millibels, 0 = none. */
    val gainMb: Int = 0,
    val focus: Boolean = false,
    val mic: Boolean = false,
    /** Wait after the switch goes on, before the sound. */
    val leadMs: Long = 300,
    /** Wait after the sound, before the switch goes off. */
    val tailMs: Long = 400,
    val durationMs: Int = 1500,
    val amplitude: Double = 0.3,
    /** A tone that does not fade, instead of the chime. */
    val steady: Boolean = false,
    /** A named sound effect (see [OutsideEffects]) instead of the chime. */
    val effect: String? = null,
    /** The navi stream volume index to set for the try (overrides [volume]); null leaves [volume] in charge. */
    val naviIndex: Int? = null,
    /** The track's own volume, 0..1, applied by the mixer before the car's amplifier. */
    val trackVolume: Float = 1f,
    /** A gain applied to the samples themselves, 0..1, before they reach the mixer at all. */
    val pcmGain: Float = 1f,
)

/**
 * Drives one exterior speaker try from a debug build: switch on, chime, switch off, with the cabin mute
 * state read at each step so the car's reaction is in the log. The switch is always switched off again,
 * on every path out, and the helper switches it off by itself if this process goes away.
 */
internal object OutsideSpeakerTest {
    private val running = AtomicBoolean(false)

    suspend fun run(context: Context, attempt: OutsideTry) {
        if (!running.compareAndSet(false, true)) {
            AppLogger.log(TAG, "A try is already running")
            return
        }
        try {
            AppLogger.log(TAG, "Try: $attempt")
            val session = OutsideSpeakerSession.open(context)
            if (session == null) {
                AppLogger.log(TAG, "No helper: the head unit's ADB connection could not be reached")
                return
            }
            try {
                when (attempt.mode) {
                    "state" -> log("state", session.command("state"))
                    "off" -> log("off", session.command("off"))
                    "switch" -> holdOnly(session, attempt)
                    else -> chime(context, session, attempt) // "chime", and "effect" with a sound
                }
            } finally {
                withContext(NonCancellable) {
                    // A read-only try must not write anything, not even the "off" that is harmless.
                    if (attempt.mode != "state") log("final off", session.command("off"))
                    session.close()
                }
            }
        } catch (t: Throwable) {
            AppLogger.logError(TAG, "Try failed", t)
        } finally {
            running.set(false)
        }
    }

    /** The switch on for [OutsideTry.durationMs] with no sound of ours: shows what the car does by itself. */
    private suspend fun holdOnly(session: OutsideSpeakerSession, attempt: OutsideTry) {
        log("baseline", session.command("state"))
        val on = session.command("on")
        log("on", on)
        if (on !is VehicleDispatchResult.Success) return
        holdFor(session, attempt.durationMs.toLong())
    }

    private suspend fun chime(context: Context, session: OutsideSpeakerSession, attempt: OutsideTry) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val effect = attempt.effect?.let { OutsideEffects.render(it) }
        if (attempt.effect != null && effect == null) {
            AppLogger.log(TAG, "No sound effect called ${attempt.effect}; known: ${OutsideEffects.catalog.joinToString { it.name }}")
            return
        }
        val rate = if (effect != null) OutsideEffects.SAMPLE_RATE else OutsideTone.SAMPLE_RATE
        val pcm = effect ?: if (attempt.steady) {
            OutsideTone.steady(attempt.durationMs, attempt.amplitude)
        } else {
            OutsideTone.chime(attempt.durationMs, attempt.amplitude)
        }
        if (attempt.pcmGain != 1f) {
            for (i in pcm.indices) pcm[i] = (pcm[i] * attempt.pcmGain).toInt().coerceIn(-32768, 32767).toShort()
            AppLogger.log(TAG, "Samples scaled by ${attempt.pcmGain}")
        }
        val durationMs = (pcm.size * 1000L / rate).toInt()
        var savedVolume: Int? = null
        var focusHeld = false
        var track: AudioTrack? = null
        var enhancer: LoudnessEnhancer? = null
        var recorder: AudioRecord? = null
        try {
            log("baseline", session.command("state"))

            val target = when {
                attempt.naviIndex != null -> attempt.naviIndex
                attempt.volume == StreamVolume.ZERO -> 0
                attempt.volume == StreamVolume.MAX -> audio.getStreamMaxVolume(attempt.streamType)
                else -> null
            }
            if (target != null) {
                savedVolume = audio.getStreamVolume(attempt.streamType)
                audio.setStreamVolume(attempt.streamType, target, 0)
                AppLogger.log(TAG, "Stream ${attempt.streamType} volume $savedVolume -> $target")
            }
            if (attempt.focus) {
                @Suppress("DEPRECATION")
                val result = audio.requestAudioFocus(null, attempt.streamType, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                AppLogger.log(TAG, "Audio focus on stream ${attempt.streamType}: result $result")
            }

            val on = session.command("on")
            log("on", on)
            if (on !is VehicleDispatchResult.Success) return
            holdFor(session, attempt.leadMs)

            if (attempt.mic) {
                recorder = startMic()
                AppLogger.log(TAG, "Microphone ${if (recorder != null) "started" else "could not be started"}")
                holdFor(session, 200)
            }

            track = buildTrack(attempt, pcm, rate)
            if (attempt.trackVolume != 1f) {
                track.setVolume(attempt.trackVolume)
                AppLogger.log(TAG, "Track volume ${attempt.trackVolume}")
            }
            if (attempt.gainMb != 0) {
                enhancer = LoudnessEnhancer(track.audioSessionId).apply {
                    setTargetGain(attempt.gainMb)
                    enabled = true
                }
            }
            track.write(pcm, 0, pcm.size)
            track.play()
            AppLogger.log(TAG, "Playing $durationMs ms")
            holdFor(session, durationMs.toLong() + attempt.tailMs)
            log("after sound", session.command("state"))
        } finally {
            withContext(NonCancellable) {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                runCatching { enhancer?.release() }
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                runCatching { if (focusHeld) @Suppress("DEPRECATION") audio.abandonAudioFocus(null) }
                runCatching { savedVolume?.let { audio.setStreamVolume(attempt.streamType, it, 0) } }
            }
        }
    }

    /** Waits [ms], sending the heartbeat the helper needs to keep the switch on. */
    private suspend fun holdFor(session: OutsideSpeakerSession, ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, HEARTBEAT_EVERY_MS)
            delay(step)
            left -= step
            val answer = session.command("hold")
            if (answer !is VehicleDispatchResult.Success) {
                log("heartbeat", answer)
                return
            }

        }
    }

    private fun buildTrack(attempt: OutsideTry, pcm: ShortArray, sampleRate: Int): AudioTrack {
        val attributes = NaviAudio.attributes(attempt.streamType, attempt.usage, attempt.contentType, attempt.flags)
        AppLogger.log(TAG, "Attributes: stream=${attempt.streamType} usage=${attributes.usage} contentType=${attributes.contentType}")
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    }

    /** The apps open the microphone for a moment before playing; why is not known. */
    private fun startMic(): AudioRecord? {
        val size = AudioRecord.getMinBufferSize(8_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            val record = try {
                @Suppress("MissingPermission")
                AudioRecord(source, 8_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            } catch (e: Exception) {
                null
            }
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                record.startRecording()
                return record
            }
            record?.release()
        }
        return null
    }

    private fun log(step: String, result: VehicleDispatchResult) = AppLogger.log(TAG, "$step: $result")

    private const val HEARTBEAT_EVERY_MS = 500L
}

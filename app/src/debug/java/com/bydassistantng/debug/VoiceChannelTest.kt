package com.bydassistantng.debug

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.bydassistantng.audio.CabinNoise
import com.bydassistantng.audio.MusicPlayback
import com.bydassistantng.util.AppLogger
import kotlin.math.PI
import kotlin.math.sin

private const val TAG = "VoiceChannel"
private const val SAMPLE_RATE = 24_000

/**
 * How one try is made. The defaults are what the car's own assistant does for its spoken replies (found by reading
 * its code): a plain track on stream 17, the car's `STREAM_BTTS`, in the legacy constructor form.
 */
internal data class VoiceTry(
    /** `play` plays a tone, `read` only logs the streams and what is playing, `noise` takes three cabin-noise readings. */
    val mode: String = "play",
    val streamType: Int = 17,
    /** `legacy` is `AudioTrack(streamType, ...)` as the assistant builds it; `attrs` is an attributes-based track. */
    val constructor: String = "legacy",
    /** What is done to quieten the music around the sound: `none`, `call` (the voice-active call the assistant
     * makes), `focus` (an ordinary audio-focus request that allows ducking) or `both`. */
    val duck: String = "none",
    val durationMs: Int = 3_000,
    val amplitude: Double = 0.3,
    /** A volume index to set on the stream for the try (put back afterwards); null leaves it alone. */
    val index: Int? = null,
)

/**
 * Plays a short two-note tone on the car's voice stream, inside the cabin, to find out whether an app that is not
 * the car's own assistant can use that stream, at which volume, and whether the music is lowered under it. It does
 * not touch the exterior speaker route. Every step is logged as "VoiceChannel: ...".
 */
internal object VoiceChannelTest {
    fun run(context: Context, attempt: VoiceTry) {
        val audio = context.getSystemService(AudioManager::class.java)
        logState(audio, "before")
        if (attempt.mode == "read") return
        // How loud a steady tone is at the microphone for several volume indexes of the voice stream.
        if (attempt.mode == "levels") {
            measureLevels(audio, attempt)
            return
        }
        // Three readings of the cabin noise, the way a reply on the voice channel takes one before it starts.
        if (attempt.mode == "noise") {
            repeat(3) {
                val level = CabinNoise.measure(attempt.durationMs, skipMs = 150)
                AppLogger.log(TAG, "Cabin noise reading: ${level?.let { "%.1f dBFS".format(it) } ?: "none"}")
            }
            return
        }

        val attributes = runCatching { AudioAttributes.Builder().setLegacyStreamType(attempt.streamType).build() }
            .onFailure { AppLogger.log(TAG, "Attributes for stream ${attempt.streamType} failed: $it") }
            .getOrNull()

        val savedIndex = attempt.index?.let { audio.getStreamVolume(attempt.streamType) }
        attempt.index?.let { index ->
            runCatching { audio.setStreamVolume(attempt.streamType, index, 0) }
                .onSuccess { AppLogger.log(TAG, "Stream ${attempt.streamType} volume set to $index (was $savedIndex)") }
                .onFailure { AppLogger.log(TAG, "Setting the stream volume failed: $it") }
        }

        val useCall = attempt.duck == "call" || attempt.duck == "both"
        val useFocus = attempt.duck == "focus" || attempt.duck == "both"
        var focusRequest: AudioFocusRequest? = null
        if (useFocus && attributes != null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change -> AppLogger.log(TAG, "Audio focus changed: $change") }
                .build()
            AppLogger.log(TAG, "Audio focus request (may duck): ${audio.requestAudioFocus(focusRequest)}")
        }
        // The first try of the call raised the media volume from 2 to 20 when it was switched off again, so the
        // media volume is noted here and put back after it.
        val savedMedia = if (useCall) audio.getStreamVolume(AudioManager.STREAM_MUSIC) else null
        if (useCall) AppLogger.log(TAG, "Voice active, duck media: ${voiceActive(audio, true, true)}")

        var track: AudioTrack? = null
        try {
            track = buildTrack(attempt, attributes)
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                AppLogger.log(TAG, "The track did not initialise (state ${track?.state})")
                return
            }
            AppLogger.log(TAG, "Track built on stream ${attempt.streamType} (${attempt.constructor}), reported stream type ${track.streamType}")
            val pcm = tone(attempt)
            track.play()
            AppLogger.log(TAG, "Playing ${attempt.durationMs} ms")
            // Written in one go: a blocking write returns as the buffer drains, so this is also the wait.
            Thread {
                Thread.sleep(500)
                logState(audio, "during")
            }.start()
            track.write(pcm, 0, pcm.size)
            Thread.sleep(300)
            track.stop()
            AppLogger.log(TAG, "Finished")
        } catch (t: Throwable) {
            AppLogger.log(TAG, "The try failed: $t")
        } finally {
            runCatching { track?.release() }
            if (useCall) {
                AppLogger.log(TAG, "Voice inactive: ${voiceActive(audio, false, false)}")
                val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (savedMedia != null && now != savedMedia) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedMedia, 0)
                    AppLogger.log(TAG, "Media volume was $now after the call; put back to $savedMedia")
                }
            }
            focusRequest?.let { AppLogger.log(TAG, "Audio focus abandoned: ${audio.abandonAudioFocusRequest(it)}") }
            if (savedIndex != null) runCatching { audio.setStreamVolume(attempt.streamType, savedIndex, 0) }
            logState(audio, "after")
        }
    }

    /**
     * Plays the same steady tone at each of a few voice volume indexes and reads how loud it is at the unprocessed
     * microphone, to find how many decibels one index step is. The cabin should be calm and the music stopped.
     */
    private fun measureLevels(audio: AudioManager, attempt: VoiceTry) {
        val original = audio.getStreamVolume(attempt.streamType)
        val tone = ShortArray(SAMPLE_RATE * 2) { (sin(2 * PI * 440.0 * it / SAMPLE_RATE) * 0.3 * Short.MAX_VALUE).toInt().toShort() }
        val baseline = CabinNoise.measure(600, 100)
        AppLogger.log(TAG, "Levels: cabin without the tone ${baseline?.let { "%.1f".format(it) }} dBFS")
        try {
            // The voice stream runs 5 to 24; the navigation stream 0 to 10.
            val indexes = if (attempt.streamType == 15) listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) else listOf(5, 6, 7, 8, 10, 12, 14)
            for (index in indexes) {
                runCatching { audio.setStreamVolume(attempt.streamType, index, 0) }
                val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
                @Suppress("DEPRECATION")
                val track = AudioTrack(attempt.streamType, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM)
                try {
                    track.play()
                    val writer = Thread { track.write(tone, 0, tone.size) }.apply { start() }
                    Thread.sleep(500)
                    val level = CabinNoise.measure(1000, 100)
                    writer.join(3000)
                    AppLogger.log(TAG, "Levels: index $index -> ${level?.let { "%.1f".format(it) }} dBFS at the microphone")
                } finally {
                    runCatching { track.stop() }
                    track.release()
                }
                Thread.sleep(600)
            }
        } finally {
            runCatching { audio.setStreamVolume(attempt.streamType, original, 0) }
        }
    }

    private fun buildTrack(attempt: VoiceTry, attributes: AudioAttributes?): AudioTrack? {
        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        return if (attempt.constructor == "attrs") {
            if (attributes == null) return null
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(attempt.streamType, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM)
        }
    }

    /** The call the car's assistant makes around a reply; a BYD addition to the audio manager, found by name. */
    private fun voiceActive(audio: AudioManager, active: Boolean, duckMedia: Boolean): String = runCatching {
        val method = AudioManager::class.java.getMethod("setAutoVoiceActiveState", java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
        "returned ${method.invoke(audio, active, duckMedia)}"
    }.getOrElse { "failed: ${it.cause ?: it}" }

    /** A two-note "ding-dong" over [VoiceTry.durationMs], each note with a soft start and a ringing end. */
    private fun tone(attempt: VoiceTry): ShortArray {
        val total = SAMPLE_RATE * attempt.durationMs / 1000
        val pcm = ShortArray(total)
        val notes = listOf(660.0 to 0.45, 520.0 to 0.65)
        var at = 0
        var round = 0
        while (at < total) {
            val (frequency, seconds) = notes[round % notes.size]
            val length = minOf((seconds * SAMPLE_RATE).toInt(), total - at)
            for (i in 0 until length) {
                val t = i.toDouble() / SAMPLE_RATE
                val attack = minOf(1.0, i / (0.01 * SAMPLE_RATE))
                val decay = Math.exp(-3.0 * i / length)
                pcm[at + i] = (sin(2 * PI * frequency * t) * attack * decay * attempt.amplitude * Short.MAX_VALUE).toInt().toShort()
            }
            at += length
            round++
        }
        return pcm
    }

    /** The streams that matter here, whether music is playing and what the system lists as playing. */
    private fun logState(audio: AudioManager, label: String) {
        val streams = listOf(3 to "media", 15 to "navi", 17 to "voice").joinToString { (type, name) ->
            val range = runCatching { "${audio.getStreamMinVolume(type)}..${audio.getStreamMaxVolume(type)}" }.getOrDefault("?")
            "$name($type)=${runCatching { audio.getStreamVolume(type) }.getOrDefault(-1)}/$range"
        }
        AppLogger.log(TAG, "[$label] $streams, music active ${audio.isMusicActive}")
        val players = runCatching { audio.activePlaybackConfigurations }.getOrDefault(emptyList())
        if (players.isEmpty()) AppLogger.log(TAG, "[$label] no active players")
        players.forEach {
            val attributes = it.audioAttributes
            AppLogger.log(TAG, "[$label] player: $attributes (usage ${attributes.usage}, content ${attributes.contentType})")
        }
        AppLogger.log(TAG, "[$label] music playing by the app's own test: ${MusicPlayback.isPlaying(audio)}")
    }
}

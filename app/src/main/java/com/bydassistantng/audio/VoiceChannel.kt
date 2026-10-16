package com.bydassistantng.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import kotlin.math.roundToInt

/**
 * What happens to the music while the assistant speaks on the voice channel. The words are what is stored.
 */
enum class MusicDuck(val key: String) {
    /** The music carries on as it is. */
    KEEP("keep"),

    /** The media volume is turned down by the chosen share while the reply is spoken, and put back after it. */
    LEVEL("level"),

    /** The car lowers the music with the system's own dip (a fixed and deep one) and brings it back after the reply. */
    LOWER("lower"),

    /** The music is paused for the reply. */
    PAUSE("pause"),
    ;

    companion object {
        val DEFAULT = LEVEL

        fun from(key: String?): MusicDuck = entries.find { it.key == key } ?: DEFAULT
    }
}

/**
 * How a conversation uses the voice channel, as chosen in Advanced settings when it started.
 *
 * @property gainSteps the cabin-noise volume's offset, in steps of the voice stream, added to what the noise asks for.
 */
data class VoiceChannelOptions(
    val enabled: Boolean,
    val music: MusicDuck,
    val adaptive: Boolean,
    val gainSteps: Int,
    val dipPercent: Int = DEFAULT_DIP_PERCENT,
) {
    companion object {
        val OFF = VoiceChannelOptions(enabled = false, music = MusicDuck.DEFAULT, adaptive = false, gainSteps = 0)

        /** How much the music is turned down, in percent of its volume, for [MusicDuck.LEVEL]. */
        val DIP_RANGE = 10..90
        const val DIP_STEP = 10
        const val DEFAULT_DIP_PERCENT = 50

        fun clampDip(percent: Int): Int = percent.coerceIn(DIP_RANGE)

        /** The range of the gain slider, in steps of the voice stream (which has 20 of them on the car it was found on). */
        val GAIN_RANGE = -6..6

        fun clampGain(steps: Int): Int = steps.coerceIn(GAIN_RANGE)
    }
}

/**
 * Whether music is playing. `AudioManager.isMusicActive` cannot be used: on this head unit it answers true all the
 * time, with nothing playing at all. The players the system lists as active are used instead.
 */
object MusicPlayback {
    /** What the voice stream's own tracks (the assistant's replies and tones) are labelled with by the car: `CONTENT_TYPE_BTTS`. */
    const val CONTENT_TYPE_VOICE = 10

    /** `CONTENT_TYPE_NAVI` on this car: a navigation prompt is not music. */
    const val CONTENT_TYPE_NAVI = 6

    fun isPlaying(audio: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            audio.activePlaybackConfigurations.any { isMusic(it.audioAttributes.usage, it.audioAttributes.contentType) }
        }.getOrDefault(false)
    }

    /** A player of the media or game kind that is not the assistant's own voice and not a navigation prompt. */
    fun isMusic(usage: Int, contentType: Int): Boolean =
        (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME) &&
            contentType != CONTENT_TYPE_VOICE && contentType != CONTENT_TYPE_NAVI
}

/** The media volume index that [MusicDuck.LEVEL] turns the music down to: [percent] lower, and at least one step lower. */
object MusicDip {
    fun target(current: Int, percent: Int): Int {
        if (current <= 1) return current
        val lowered = Math.round(current * (100 - VoiceChannelOptions.clampDip(percent)) / 100.0).toInt()
        return lowered.coerceIn(0, current - 1)
    }

    /** The pause between two volume steps of a fade, so that the fade takes about four tenths of a second. */
    fun rampStepMs(steps: Int): Long = (400L / steps.coerceAtLeast(1)).coerceIn(20L, 200L)
}

/**
 * The car's voice channel: the audio stream its own assistant speaks on (stream 17, `STREAM_BTTS`). It has a volume of
 * its own, apart from the music, and a track on it can ask for audio focus to have the music lowered or paused.
 * Found by reading the car's assistant and tried on the car (see docs/byd-internals/voice-channel.md). It is offered
 * only where the stream looks exactly as it did there.
 */
object VoiceChannel {
    const val STREAM = 17

    /** The voice stream's volume range on the car it was found on. Another range means another car or firmware. */
    private const val EXPECTED_MIN_INDEX = 5
    private const val EXPECTED_MAX_INDEX = 24

    /** Whether the voice channel is offered here: the car has the voice stream, and it is the one that was measured. */
    fun isAvailable(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasStream(context)

    /**
     * Whether stream 17 is the voice stream of the car this was developed on. A phone has no such stream (asking for
     * it throws), and another BYD model may number its streams differently, so stream 17 might be something else
     * there: it is only used when its range is 5 to 24, as measured.
     */
    fun hasStream(context: Context): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java)
        minIndex(audio) == EXPECTED_MIN_INDEX && audio.getStreamMaxVolume(STREAM) == EXPECTED_MAX_INDEX
    }.getOrDefault(false)

    /** The lowest volume index of the voice stream (the car's is 5; before Android 9 there is no way to ask, and it is 0). */
    fun minIndex(audio: AudioManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(STREAM) else 0

    /** The attributes that put a track on the voice stream: the car labels it with its own content type from there. */
    fun attributes(): AudioAttributes = AudioAttributes.Builder().setLegacyStreamType(STREAM).build()
}

/**
 * The voice stream's volume for the noise in the cabin: louder as the cabin gets louder, between a comfortable
 * level for a quiet cabin and the top of the stream, moved up or down by the user's gain.
 *
 * The two levels the noise runs between and the share of the range the quiet end sits at were fitted to the volumes
 * the driver chose, with the gain slider, over a range of cabin noises on one car (about -60 dBFS at idle, -40 with
 * the music up); they are the part to adjust for another car.
 */
object VoiceVolume {
    /** The cabin noise (dBFS of the microphone) at which the quiet end applies, and the one at which the top does. */
    const val QUIET_DBFS = -62.0
    const val LOUD_DBFS = -22.0

    /**
     * Steps added to the whole fitted line. The driver found the line slightly loud with the slider at 0 (-2), and then,
     * once a calm cabin's volume could go below the stream's lowest index, too quiet (+3 back): +1 in all.
     */
    const val BASE_OFFSET_STEPS = 1

    /** Where in the stream's range the quiet end sits (0 is the lowest index, 1 the highest). */
    const val QUIET_FRACTION = 0.0

    /**
     * How much quieter than the lowest index one step is, in decibels, once the curve asks for less than the stream
     * can give. Measured on the car as the tone's level at the microphone: about 2 dB a step across the range, more
     * (about 4) at the lowest indexes.
     */
    const val DB_PER_STEP_BELOW_FLOOR = 2.0

    /** The most the track itself is made quieter than the stream's lowest index. */
    const val MAX_ATTENUATION_DB = 20.0

    /** What a reply is played at: the stream's volume index, and how much quieter than that the track is made. */
    data class Level(val index: Int, val attenuationDb: Double) {
        /** The track's own volume (0..1) that gives [attenuationDb]. */
        val trackVolume: Float get() = Math.pow(10.0, -attenuationDb / 20.0).toFloat()
    }

    fun indexFor(noiseDbfs: Double, gainSteps: Int, min: Int, max: Int): Int = levelFor(noiseDbfs, gainSteps, min, max).index

    /**
     * The level for the cabin noise. Below the stream's lowest index (a calm cabin, or the slider turned down) the
     * stream stays at its lowest and the track is made quieter instead, [DB_PER_STEP_BELOW_FLOOR] a step, so that the
     * slider still does something in a calm cabin.
     */
    fun levelFor(noiseDbfs: Double, gainSteps: Int, min: Int, max: Int): Level {
        if (max <= min) return Level(min, 0.0)
        val t = ((noiseDbfs - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS)).coerceIn(0.0, 1.0)
        val share = QUIET_FRACTION + (1 - QUIET_FRACTION) * t
        val wanted = min + (max - min) * share + BASE_OFFSET_STEPS + VoiceChannelOptions.clampGain(gainSteps)
        val index = wanted.roundToInt().coerceIn(min, max)
        val below = if (wanted < min) ((min - wanted) * DB_PER_STEP_BELOW_FLOOR).coerceAtMost(MAX_ATTENUATION_DB) else 0.0
        return Level(index, below)
    }
}

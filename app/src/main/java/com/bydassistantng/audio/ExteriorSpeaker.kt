package com.bydassistantng.audio

import android.media.AudioTrack
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Provider
import javax.inject.Singleton

/**
 * How loud something is made outside the car. The car levels the exterior output itself: neither the navi
 * stream's volume nor the mixer's track volume changed it when tried on a real car (4 against 10, 100%
 * against 10%). What did is the audio itself, through an audio-effects loudness boost on the exterior track:
 * +10, +20 and +30 dB were each clearly louder, with no distortion, and +60 dB was judged "high" — while the
 * unboosted sound, even at full level, was too quiet to be heard clearly a couple of metres away. So a level
 * is a [boostMb] (millibels): the lower levels are lower boosts, not a quieter signal (a signal scaled down
 * from the unboosted level was barely audible).
 */
enum class ExteriorVolume(val word: String, val boostMb: Int) {
    LOW("low", boostMb = 3_000), // +30 dB: heard nearby
    MEDIUM("medium", boostMb = 4_500), // +45 dB
    HIGH("high", boostMb = 6_000), // +60 dB: the most tried, judged loud
    ;

    companion object {
        val DEFAULT = MEDIUM

        /** The level named by [word], or null when it names none. */
        fun from(word: String?): ExteriorVolume? = entries.find { it.word.equals(word?.trim(), ignoreCase = true) }
    }
}

/** A sound the exterior speaker can play on request, by the name the model uses. */
data class SoundEffect(val name: String, val description: String)

/**
 * The car's exterior speaker as a place the assistant's voice can come out of: while it is open, what a
 * reply plays is heard outside the car only.
 *
 * It exists only on a car whose framework names the exterior speaker switch the way it was verified (see
 * [ExteriorSpeakerSwitch]); anywhere else the assistant cannot use it and never offers the model the tools
 * for it (see [SpeakOutsideTool]). Even then it stays off until the user switches it on in Advanced settings.
 */
interface ExteriorSpeaker {
    /** Whether [open] can answer at once, because what it needs is already running. */
    val isReady: Boolean get() = false

    /** Gets ready in the background, so that [open] later takes as little time as possible. Never switches anything on. */
    fun prepare() {}

    /**
     * Switches the car over and gets it ready. False when that could not be done, in which case nothing is
     * left switched on. Every successful [open] is ended by one [close]. How loud it is made is up to the
     * track's loudness boost ([ExteriorVolume.boostMb]), not to this.
     */
    suspend fun open(): Boolean

    /** A track whose sound comes out of the exterior speaker only; valid while it is open. */
    fun newTrack(sampleRate: Int, bufferSizeBytes: Int): AudioTrack

    /** Puts the car back as it was, once every [open] has been closed. Safe to call at any time. */
    fun close()

    /** The sound effects this speaker can play, by name. */
    val soundEffects: List<SoundEffect> get() = emptyList()

    /** The effect named [name] as 24 kHz mono 16-bit PCM, or null when there is none of that name. */
    fun renderEffect(name: String): ShortArray? = null
}

/** Provides the exterior speaker where the car has one, and nothing where it does not. */
@Module
@InstallIn(SingletonComponent::class)
internal object ExteriorSpeakerModule {
    @Provides
    @Singleton
    fun exteriorSpeaker(speaker: Provider<CarExteriorSpeaker>): Optional<ExteriorSpeaker> =
        if (ExteriorSpeakerSwitch.isOnThisCar()) {
            Optional.of(speaker.get())
        } else {
            Optional.empty()
        }
}

package com.bydassistantng.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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

    /**
     * Gets ready in the background, so that [open] later takes as little time as possible. Never switches anything on. The result says, once it
     * is known, whether there is a helper to open with: with ADB not allowed yet that can take a minute (the car's prompt is left up), which is
     * why a caller that has someone waiting for it looks at it with a press able to end the wait (see [awaitHelperOrStop]). Nobody has to look.
     */
    fun prepare(): Deferred<Boolean> = CompletableDeferred(true)

    /**
     * Switches the car over and gets it ready. False when that could not be done, in which case nothing is
     * left switched on. Every successful [open] is ended by one [close]. How loud it is made is up to the
     * track's loudness boost ([ExteriorVolume.boostMb]), not to this.
     *
     * It returns once the route has had its [routeSettleMs] to take effect, unless [settle] is false: then it returns as soon as the car has
     * taken the switch, and the caller, who has other things to get ready meanwhile, waits the settling out itself before the first sound.
     */
    suspend fun open(settle: Boolean = true): Boolean

    /** How long after the switch is taken the route needs before a sound is certain to come out of it (what [open] waits, unless told not to). */
    val routeSettleMs: Long get() = 300L

    /** A track whose sound comes out of the exterior speaker only; valid while it is open. */
    fun newTrack(sampleRate: Int, bufferSizeBytes: Int): AudioTrack

    /** Whether the media route (the music stream sent outside, a much faster path than the navigation stream's) can be used on this car and build. */
    val hasMediaRoute: Boolean get() = false

    /**
     * Like [open], over the media route: what is played on the **music** stream ([MusicAudio.attributes]) is heard outside. False when it cannot
     * be made, in which case nothing is left on. Closed by the same [close].
     */
    suspend fun openMedia(settle: Boolean = true): Boolean = false

    /**
     * A track for live audio (the microphone as it is said): the exterior speaker's like [newTrack], but asking the platform for its
     * lowest-latency path ([lowLatency]) and for no more buffer than [bufferFactor] times the least it gives, since every stretch of buffer
     * is a delay between speaking and being heard. [attributes] say which stream it is on; the default is the navigation stream the
     * assistant's replies use. [startThresholdMs] is how much sound must be waiting in the track before it starts playing (0: the
     * platform's own choice, which is all of its buffer); where the platform has it (Android 12 and later) a smaller one starts sooner
     * and, after the sound has run dry, starts again sooner. [minBufferBytes] is the least the buffer may be (0: [bufferFactor] times the platform's).
     */
    fun newLiveTrack(
        sampleRate: Int,
        attributes: AudioAttributes = NaviAudio.attributes(),
        lowLatency: Boolean = true,
        bufferFactor: Int = 1,
        startThresholdMs: Int = 0,
        minBufferBytes: Int = 0,
    ): AudioTrack = newTrack(sampleRate, AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * bufferFactor)

    /** Puts the car back as it was, once every [open] has been closed. Safe to call at any time. */
    fun close()

    /**
     * Switches a route off that an earlier run may have left on: the app was killed, or the helper holding the route died, in the middle of
     * a broadcast, and the car goes on sending its music output (or the navigation stream) outside until something writes 0. The app remembers
     * that a route was switched on until it has seen the switch-off taken; this looks at that and, when it says a route may be on, has a fresh
     * helper switch both off. Does nothing when there is nothing to put back, or while a route is in use. Safe to call at any time.
     */
    fun recoverLeftRoute() {}

    /**
     * Tells [listener] when the route that [open] switched on can no longer be relied on (the helper that holds it stopped answering,
     * so it is about to switch itself off, or has): whoever is playing into it must stop, or its sound comes out of the cabin.
     * One listener at a time; null removes it. Not called for a [close].
     */
    fun setRouteLostListener(listener: (() -> Unit)?) {}

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

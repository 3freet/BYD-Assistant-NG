package com.bydassistantng.audio

/**
 * Where the music goes while a live broadcast is on. The car sends the whole music stream out of the exterior speaker on its fast route, so the
 * choice is also a choice of route.
 */
enum class MusicMode(val word: String) {
    /** The fast route, with the music stopped first (the default): the voice has the exterior speaker to itself. */
    PAUSED("paused"),

    /** The fast route, with the music going out of the car along with the voice. */
    OUTSIDE("outside"),

    /** The slow navigation route, which leaves the music playing in the cabin: nothing of it is heard outside. */
    INSIDE("inside"),
    ;

    companion object {
        val DEFAULT = PAUSED

        fun from(word: String?): MusicMode? = entries.find { it.word == word }

        /** The choice as it was saved: the word when there is one, else what the older music-outside setting said. */
        fun fromSaved(word: String?, musicOutsideBefore: Boolean?): MusicMode =
            from(word) ?: if (musicOutsideBefore == true) OUTSIDE else DEFAULT
    }
}

/**
 * How loud the voice is made on the exterior speaker in a live broadcast: the three levels the assistant's own outside replies have
 * ([ExteriorVolume]), each meaning what suits the route.
 *
 * On the fast (media) route the car's own volume sets the loudness and the voice is not boosted by the platform (its compressor with make-up gain
 * rang the loop within a second), so a level is a plain gain on the voice: 0, 6 and 12 dB, never past what the chain's soft clip can take. On the
 * navigation route, whose output is too quiet to be heard unboosted, a level is the platform's loudness boost on the track as for the assistant's
 * replies, but in smaller steps (15, 30 and 45 dB against their 30, 45 and 60): the boost lifts what the microphone hears of the speaker just as
 * much as the voice, and on a route whose way round is half a second that is what rings.
 */
internal object BroadcastLoudness {
    val DEFAULT = ExteriorVolume.MEDIUM

    fun mediaGain(level: ExteriorVolume): Float = when (level) {
        ExteriorVolume.LOW -> 1.0f
        ExteriorVolume.MEDIUM -> 2.0f
        ExteriorVolume.HIGH -> 4.0f
    }

    fun boostMb(level: ExteriorVolume): Int = when (level) {
        ExteriorVolume.LOW -> 1_500
        ExteriorVolume.MEDIUM -> 3_000
        ExteriorVolume.HIGH -> 4_500
    }
}

package com.bydassistantng.audio

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How easily a sound is taken for the user speaking: one setting for the two levels that decide it, in the conversation's
 * own (processed) microphone signal, where speech peaks at 5,000 and more and a cabin's bumps and hiss are well under:
 *
 *  - the peak a sound has to reach to count as a sentence at all ([utterancePeak]); anything fainter is "a faint sound" and
 *    ignored, instead of being sent off and waited on;
 *  - the level a sound has to reach to count as the user talking over the assistant ([bargeInRms]).
 *
 * Each step multiplies both by [STEP_FACTOR] one way or the other: positive steps pick up a softer voice and are easier to
 * fool with noise; negative steps need a firmer voice. 0 is what the app was fitted to on the one car it was tried on; another
 * car's microphone, cabin and road noise may want a different step. The most sensitive step stays at about 800 for talking
 * over the assistant, below which weak triggers produced junk transcripts and cut replies off for nothing.
 */
object VoiceSensitivity {
    val RANGE = -3..3
    const val DEFAULT = 0

    /** What a step multiplies the two levels by. */
    const val STEP_FACTOR = 1.15

    const val BASE_UTTERANCE_PEAK = 1_500
    const val BASE_BARGE_IN_RMS = 1_200

    fun clamp(step: Int): Int = step.coerceIn(RANGE)

    /** What the levels are multiplied by at [step]: 1 at 0, less above it (more sensitive), more below it. */
    fun factor(step: Int): Double = STEP_FACTOR.pow(-clamp(step).toDouble())

    fun utterancePeak(step: Int): Int = (BASE_UTTERANCE_PEAK * factor(step)).roundToInt()

    fun bargeInRms(step: Int): Int = (BASE_BARGE_IN_RMS * factor(step)).roundToInt()
}

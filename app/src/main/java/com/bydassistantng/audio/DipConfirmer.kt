package com.bydassistantng.audio

/**
 * The second opinion on a barge-in. [BargeInDetector] can be fooled by a short burst of sound at the
 * start of a reply — the assistant's own voice before the echo canceller has settled, a window or
 * seat-massage motor starting — which sits just above its bar for a fraction of a second. The user's voice
 * can't be told from that at the moment of detection, but a moment later it can: the controller turns the
 * reply's volume right down for a few hundred milliseconds, which takes the echo with it, and this class
 * watches what the mic does next. Speech carries on at full level; a burst or an echo falls away.
 *
 * Feed it one mic RMS per 50ms chunk after [begin].
 */
class DipConfirmer(
    /** How long to watch, in chunks, before deciding it wasn't the user. */
    private val windowChunks: Int = 8,
    /** Loud chunks needed to call it speech. They need not be consecutive: speech dips between words. */
    private val confirmChunks: Int = 3,
    /** What counts as loud. Below the detector's own bar on purpose: the first words were what tripped
     * it, and a quiet talker's next words are no louder; the dip already removed the echo that a lower
     * level could be mistaken for. */
    private val confirmRms: Int = 900,
) {
    enum class Verdict { PENDING, CONFIRMED, REJECTED }

    private var seen = 0
    private var loud = 0

    fun begin() {
        seen = 0
        loud = 0
    }

    fun onChunk(micRms: Int): Verdict {
        seen++
        if (micRms >= confirmRms) loud++
        return when {
            loud >= confirmChunks -> Verdict.CONFIRMED
            seen >= windowChunks -> Verdict.REJECTED
            else -> Verdict.PENDING
        }
    }
}

package com.bydassistantng.audio

/**
 * Keeps the words of a song out of what is sent to the server as the user speaking, while music is playing.
 *
 * The microphone hears the music as well as the user, and the server takes sung words for speech. The music's level is
 * fairly steady, and the user, close to the microphone, is clearly louder than it, so the gate learns how loud the
 * background is (the music and the cabin) from the chunks that are not the user, and sends only what is clearly above it:
 * everything else goes as silence of the same length, so the timing the server hears is unchanged.
 *
 *  - **Background:** a high percentile of the recent levels that were not above the bar, so the louder moments of the music
 *    count, not only the quietest. The bar is that background times a multiplier (about 1.5 to 2), and never less than
 *    [MIN_BAR]. The user's own voice is above the bar and never feeds the background.
 *  - **Lookahead:** the chunks are held back by [lookahead] before they are sent, so that the first soft sound of a word,
 *    which is below the bar, is sent when the louder part just after it is seen: nothing of a word is clipped.
 *  - **Hangover:** after a loud chunk the next [hangover] go through too, so the pauses between words, and the soft ends of
 *    them, are not cut.
 *
 * It fails open: if the music is so loud that everything is above the bar, everything is sent, as without the gate. The
 * cost is the other side: a user who is not clearly louder than the music is cut. Used only while music is playing.
 */
class LevelGate(
    /** Chunks held back before they are sent. At 50 ms a chunk that is 150 ms of delay, the most the first word can be late. */
    private val lookahead: Int = 3,
    /** Chunks sent after the last loud one. */
    private val hangover: Int = 6,
    /** How many recent background levels are kept (3 s of 50 ms chunks). */
    private val window: Int = 60,
    /** The background is not trusted until this many levels are known; until then everything is sent. */
    private val minKnown: Int = 8,
) {
    private class Held(val chunk: ByteArray, val loud: Boolean)

    private val held = ArrayDeque<Held>()
    private val background = ArrayDeque<Int>()
    private var hangLeft = 0

    /** Chunks seen and chunks sent as silence since [beginTurn]. */
    var chunksSeen = 0
        private set
    var chunksSilenced = 0
        private set

    /** The bar of the last chunk seen (0 until the background is known), for the log. */
    var bar = 0
        private set

    /** The background level the bar is built on (0 until it is known), for the log. */
    val backgroundLevel: Int get() = percentile()

    /**
     * Takes the next chunk, with its level, and returns the chunk to send now: the real one, silence of the same length, or
     * null while the first [lookahead] chunks are being held. [multiplier] is how many times the background the bar is.
     */
    fun process(chunk: ByteArray, level: Int, multiplier: Double): ByteArray? {
        val known = background.size >= minKnown
        val barNow = if (known) maxOf(MIN_BAR.toDouble(), percentile() * multiplier) else 0.0
        bar = barNow.toInt()
        // Until the background is known nothing is above or below it: the chunk is background, and is let through.
        val loud = known && level >= barNow
        if (!loud) {
            background.addLast(level)
            while (background.size > window) background.removeFirst()
        }
        held.addLast(Held(chunk, loud || !known))
        chunksSeen++
        if (held.size <= lookahead) return null

        val head = held.removeFirst()
        val pass = head.loud || held.any { it.loud } || hangLeft > 0
        if (head.loud) hangLeft = hangover else if (hangLeft > 0) hangLeft--
        if (pass) return head.chunk
        chunksSilenced++
        return ByteArray(head.chunk.size)
    }

    /** A new turn begins: what was held is dropped, and the counts start again; what is known of the background is kept. */
    fun beginTurn() {
        held.clear()
        hangLeft = 0
        chunksSeen = 0
        chunksSilenced = 0
    }

    /** A new conversation: the background is learnt afresh. */
    fun reset() {
        beginTurn()
        background.clear()
        bar = 0
    }

    private fun percentile(): Int {
        if (background.isEmpty()) return 0
        val sorted = background.sorted()
        return sorted[(BACKGROUND_PERCENTILE * (sorted.size - 1)).toInt()]
    }

    companion object {
        /** However quiet the background, nothing below this is sent: a cabin's own bumps are not the user. */
        const val MIN_BAR = 120

        /** Which of the recent background levels counts as "the background": the loud end of the music, not its quiet moments. */
        const val BACKGROUND_PERCENTILE = 0.8

        /** The bar as a multiple of the background at the middle sensitivity, and how far a step of the sensitivity moves it. */
        const val BASE_MULTIPLIER = 1.7
        const val MULTIPLIER_SPREAD = 0.7

        /**
         * The multiplier for the voice sensitivity setting: the same sensitivity that sets how firm a sound must be to count as
         * the user moves this too, so one slider tunes both (more sensitive, a lower bar).
         */
        fun multiplier(sensitivityStep: Int): Double = 1.0 + MULTIPLIER_SPREAD * VoiceSensitivity.factor(sensitivityStep)
    }
}

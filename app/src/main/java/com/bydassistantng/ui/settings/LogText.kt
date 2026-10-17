package com.bydassistantng.ui.settings

/**
 * How much of a log is shown and shared. A log can be a megabyte, which is too much to scroll through from the top and
 * far more than Android lets one share intent carry (about 1 MB for everything in flight, and text counts double): the
 * newest part is what matters, so that is what is kept.
 */
internal object LogText {
    const val MAX_CHARS = 100_000

    /** What was kept of a log: [text] (the newest lines, whole lines) and the length of the log it came from. */
    data class Kept(val text: String, val totalChars: Int) {
        val isCut: Boolean get() = text.length < totalChars
    }

    fun newest(log: String, maxChars: Int = MAX_CHARS): Kept {
        if (log.length <= maxChars) return Kept(log, log.length)
        val from = log.length - maxChars
        // From the start of the first whole line inside the limit, so nothing begins in the middle of one.
        val lineStart = log.indexOf('\n', from).let { if (it < 0) from else it + 1 }
        return Kept(log.substring(lineStart), log.length)
    }
}

package com.bydassistantng.gemini

/** What the assistant said when it opened a conversation itself (the greeting), and whether it wants an answer. */
object OpeningReply {
    /**
     * Whether [spoken] (the transcript of what was said) ends in a question, which is the one sign that the driver is
     * being asked for something. Anything else is a statement, and the conversation is over once it has been heard.
     */
    fun asksSomething(spoken: String): Boolean {
        val last = spoken.trimEnd { it.isWhitespace() || it in CLOSERS }.lastOrNull() ?: return false
        return last == '?' || last == '؟'
    }

    private const val CLOSERS = "\"'”’)»…"
}

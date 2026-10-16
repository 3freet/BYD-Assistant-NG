package com.bydassistantng.vehicle

/**
 * The car's headrest sound (Devialet Headrest Sound, the driver's headrest speaker), as the assistant reads and sets it
 * to speak from that speaker. The setter is the registry's `audio.headrestSound`; this holds what is needed to read it.
 *
 * The modes, from the car's own screen and tried by ear: **surround** plays everything on the car's speakers and the
 * headrest; **interactive** plays calls and the navigation stream on the headrest only, and leaves the music (and the
 * voice stream, which it does not move) in the car; **exclusive** puts the music in the headrest.
 */
object HeadrestSound {
    /** The registry command that sets the mode. */
    const val COMMAND_ID = "audio.headrestSound"

    const val MODE_OFF = 1
    const val MODE_SURROUND = 2
    const val MODE_INTERACTIVE = 3
    const val MODE_EXCLUSIVE = 4

    /** The audio device, the state that reads the mode back, and the one that says how many headrest speakers there are. */
    internal const val DEVICE_TYPE = 1002
    internal const val MODE_STATE_ID = 1253048341
    internal const val SPEAKER_CONFIG_STATE_ID = 1253048344

    /** What the car reported: the mode (one of the `MODE_` numbers) and the speaker configuration, each null when unread. */
    data class State(val mode: Int?, val speakers: Int?) {
        /** The car has a headrest speaker, and reports a mode for it. */
        val hasSpeaker: Boolean get() = (speakers ?: 0) > 0 && mode in MODE_OFF..MODE_EXCLUSIVE
    }

    fun encode(state: State): String = "mode=${state.mode ?: UNREAD} speakers=${state.speakers ?: UNREAD}"

    fun parse(note: String?): State? {
        if (note == null) return null
        val values = note.trim().split(' ').mapNotNull {
            val (key, value) = it.split('=', limit = 2).takeIf { parts -> parts.size == 2 } ?: return@mapNotNull null
            key to value.toIntOrNull()
        }.toMap()
        if ("mode" !in values || "speakers" !in values) return null
        return State(mode = values["mode"]?.takeIf { it != UNREAD }, speakers = values["speakers"]?.takeIf { it != UNREAD })
    }

    private const val UNREAD = -1
}

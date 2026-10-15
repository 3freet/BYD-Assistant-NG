package com.bydassistantng.vehicle

/**
 * What the model is told when a command was accepted. The car's own notes are for the log ("HAL returned 0" means
 * the setter was accepted, nothing more), and the model reads them as "did not work" and tries again, so an answer
 * that cannot be checked says what to do next. A confirmed one is left as it is: the car reported the new value.
 */
internal object CommandAnswers {
    const val REPEAT_NOTE = "Already sent a moment ago; it is in effect or on its way. Do not send it again; tell the user it was sent."

    const val UNCHECKED_NOTE =
        "Sent to the car. This control gives no confirmation, so it cannot be checked. Do not send it again; tell the user it was sent."

    private const val SERVICE_ACCEPTED_NOTE = "Done: the service accepted it. Do not send it again; tell the user it is done."

    private val underWay = Regex("""under way: now at (-?\d+), heading for (-?\d+)""")

    fun forModel(note: String?): String? {
        if (note.isNullOrBlank()) return UNCHECKED_NOTE
        underWay.find(note)?.let { m ->
            return "On its way: now at ${m.groupValues[1]}, heading for ${m.groupValues[2]}. " +
                "Do not send it again; tell the user it is moving."
        }
        return when {
            note.startsWith("HAL returned") || note.startsWith("sent, but") -> UNCHECKED_NOTE
            note.startsWith("the service accepted") -> SERVICE_ACCEPTED_NOTE
            else -> note
        }
    }
}

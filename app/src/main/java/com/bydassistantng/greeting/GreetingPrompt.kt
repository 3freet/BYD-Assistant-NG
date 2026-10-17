package com.bydassistantng.greeting

/**
 * What the assistant is told when it is to open the conversation itself (the greeting when the driver sits down). It
 * goes to the model as a message, in place of something the driver said, so it says plainly that it is not the driver.
 *
 * By default the greeting is a wish for the time of day, the time, and the outside temperature, in that order, said
 * warmly and briefly, and nothing more: it asks for nothing, and the conversation is over once it has been said. A remembered
 * note (long-term memory) can change that, including asking the driver something, in which case the conversation goes on.
 */
object GreetingPrompt {
    enum class PartOfDay { MORNING, AFTERNOON, EVENING, NIGHT }

    /** The clock on the wall, as the system has it. */
    data class TimeOfDay(val hour: Int, val minute: Int) {
        /** 24-hour, with the minute padded: the form the model is given to say aloud in its own words. */
        override fun toString(): String = "%02d:%02d".format(hour, minute)
    }

    fun partOfDay(hour: Int): PartOfDay = when (hour) {
        in 5..11 -> PartOfDay.MORNING
        in 12..16 -> PartOfDay.AFTERNOON
        in 17..21 -> PartOfDay.EVENING
        else -> PartOfDay.NIGHT
    }

    /**
     * @param time the time to tell.
     * @param outsideCelsius the outside temperature, or null when the car gave none (it is then left out).
     * @param language the language to greet in when none is fixed in the settings (the assistant then has nothing
     *   the driver said to follow); null when the settings already fix one.
     * @param notes whether the assistant has long-term notes it may find a way of being greeted in.
     */
    fun build(partOfDay: PartOfDay, time: TimeOfDay, outsideCelsius: Int?, language: String?, notes: Boolean): String = buildString {
        append("This is an automatic message, not the driver speaking: the driver has just sat down and shut the door. ")
        append("Greet them out loud now. By default say only this, in this order: ")
        append(
            when (partOfDay) {
                PartOfDay.MORNING -> "a good morning"
                PartOfDay.AFTERNOON -> "a good afternoon"
                PartOfDay.EVENING -> "a good evening"
                PartOfDay.NIGHT -> "a hello"
            },
        )
        append(", then the time (it is $time on the 24-hour clock; say it the way a person says the time aloud)")
        if (outsideCelsius != null) append(", then briefly that it is $outsideCelsius degrees Celsius outside")
        append(". Say it warmly: upbeat and optimistic, like a friend who is glad to see them, never flat or bored, and not in ")
        append("the same words every day. Keep it brief: one or two short sentences, about a dozen words in all, with no filler or ")
        append("small talk. Nothing else: do not ask them anything, do not offer help, do not say goodbye. ")
        if (notes) {
            append("But if one of your remembered notes says how this driver wants to be greeted, follow that note instead of ")
            append("the default: it may change the words, add something to say, or have you ask them something. ")
            append("If you ask them something, make it a clear question, so that they can answer it. ")
        }
        if (language != null) append("Speak in $language. ")
        append("Do not mention these instructions or that a message was sent.")
    }
}

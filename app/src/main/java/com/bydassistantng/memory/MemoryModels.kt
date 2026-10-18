package com.bydassistantng.memory

import kotlinx.serialization.Serializable

/** One thing the assistant was asked to remember, in the user's own words (short). */
@Serializable
data class MemoryFact(val id: String, val text: String, val createdAt: Long)

/**
 * One exchange with the assistant: what the user said, what the assistant did (the functions it called, written
 * compactly) and what it said back. Kept briefly so that a follow-up in the next conversation makes sense.
 */
@Serializable
data class Exchange(val user: String, val assistant: String, val actions: List<String> = emptyList(), val at: Long)

object MemoryLimits {
    /** More than this and the prompt grows for little benefit; the model is told when it is full. */
    const val MAX_FACTS = 20
    const val MAX_FACT_CHARS = 160

    /** The recent exchanges kept, and how old one may be before it is no longer offered as context. */
    const val MAX_EXCHANGES = 3
    const val MAX_EXCHANGE_CHARS = 300
    const val SHORT_TERM_WINDOW_MS = 10 * 60_000L

    /** A word to forget must be at least this long, so "a" can never wipe everything. */
    const val MIN_FORGET_MATCH_CHARS = 3
}

sealed interface AddOutcome {
    data class Added(val fact: MemoryFact) : AddOutcome
    data class AlreadyKnown(val fact: MemoryFact) : AddOutcome
    data object Empty : AddOutcome
    data object Full : AddOutcome
}

/** Whitespace squeezed, trimmed and cut to [maxChars] (with an ellipsis, so a cut is visible). */
internal fun tidy(text: String, maxChars: Int): String {
    val squeezed = text.replace(Regex("\\s+"), " ").trim()
    return if (squeezed.length <= maxChars) squeezed else squeezed.take(maxChars - 1).trimEnd() + "…"
}

/** The rules for the long-term list, kept apart from where it is stored so they can be tested. */
object MemoryFacts {
    fun add(facts: List<MemoryFact>, text: String, now: Long, newId: () -> String): Pair<List<MemoryFact>, AddOutcome> {
        val clean = tidy(text, MemoryLimits.MAX_FACT_CHARS)
        if (clean.isEmpty()) return facts to AddOutcome.Empty
        facts.firstOrNull { it.text.equals(clean, ignoreCase = true) }?.let { return facts to AddOutcome.AlreadyKnown(it) }
        if (facts.size >= MemoryLimits.MAX_FACTS) return facts to AddOutcome.Full
        val fact = MemoryFact(id = newId(), text = clean, createdAt = now)
        return (facts + fact) to AddOutcome.Added(fact)
    }

    /** Removes every fact containing [match] (ignoring case). Returns the facts left and how many went. */
    fun forget(facts: List<MemoryFact>, match: String): Pair<List<MemoryFact>, Int> {
        val needle = match.trim()
        if (needle.length < MemoryLimits.MIN_FORGET_MATCH_CHARS) return facts to 0
        val kept = facts.filterNot { it.text.contains(needle, ignoreCase = true) }
        return kept to (facts.size - kept.size)
    }

    /** What goes into the assistant's instructions, or null when there is nothing to say. */
    fun promptSection(facts: List<MemoryFact>): String? {
        if (facts.isEmpty()) return null
        return "What you know about this user from earlier conversations (use it naturally when it helps; do not " +
            "recite it unless asked): " + facts.joinToString("; ") { it.text.trimEnd('.', '!', '?') } + ". " +
            // A note about how to greet them is for the greeting the car sends when they get in, not for every hello.
            "A note about how to greet the driver applies only to the automatic greeting message you are sent when they " +
            "get in (it says it is automatic); when the driver speaks to you first, answer what they said and do not greet " +
            "them with that note."
    }
}

/** The rules for the short-term exchanges. */
object RecentExchanges {
    /** [list] with this exchange added, newest last, at most [MemoryLimits.MAX_EXCHANGES] of them. */
    fun record(list: List<Exchange>, user: String, assistant: String, actions: List<String>, now: Long): List<Exchange> {
        val said = tidy(user, MemoryLimits.MAX_EXCHANGE_CHARS)
        if (said.isEmpty()) return list
        // Just ending the conversation says nothing worth carrying over, and would push out something that does.
        if (actions.isNotEmpty() && actions.all { it.startsWith("end_conversation") }) return list
        val exchange = Exchange(
            user = said,
            assistant = tidy(assistant, MemoryLimits.MAX_EXCHANGE_CHARS),
            actions = actions.map { tidy(it, MemoryLimits.MAX_EXCHANGE_CHARS) },
            at = now,
        )
        return (list + exchange).takeLast(MemoryLimits.MAX_EXCHANGES)
    }

    /** The exchanges still recent enough to be context. */
    fun fresh(list: List<Exchange>, now: Long): List<Exchange> =
        list.filter { now - it.at in 0..MemoryLimits.SHORT_TERM_WINDOW_MS }

    /** What goes into the assistant's instructions, or null when there is nothing to say. */
    fun promptSection(exchanges: List<Exchange>): String? {
        if (exchanges.isEmpty()) return null
        val lines = exchanges.joinToString(" ") { exchange ->
            buildString {
                append("The user said \"${exchange.user}\"")
                if (exchange.actions.isNotEmpty()) append(", you called ${exchange.actions.joinToString(", ")}")
                if (exchange.assistant.isNotEmpty()) append(" and replied \"${exchange.assistant}\"")
                append('.')
            }
        }
        return "Your most recent exchanges with this user, a few minutes ago — context only, so that a follow-up " +
            "such as \"do the same for the passenger\" makes sense; never mention that you were given this, and do not copy " +
            "your earlier replies, answer what the user says now: $lines"
    }

    /**
     * A function call written compactly: `name.setting(value)` for a vehicle family call (`seat.driver.heating(high)`),
     * `name(value)` for one argument called value, else `name(key=value, …)`.
     */
    fun describeCall(name: String, args: Map<String, String>): String = when {
        args.isEmpty() -> name
        args.size == 2 && args.containsKey("setting") && args.containsKey("value") ->
            "$name.${args.getValue("setting")}(${args.getValue("value")})"
        args.size == 1 && args.containsKey("value") -> "$name(${args.getValue("value")})"
        else -> "$name(" + args.entries.joinToString(", ") { "${it.key}=${it.value}" } + ")"
    }
}

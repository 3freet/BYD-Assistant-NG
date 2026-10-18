package com.bydassistantng.memory

import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the assistant needs to know about its memory in this conversation. */
data class MemoryContext(
    /** The `remember` and `forget_memory` tools are offered (long-term memory is on). */
    val tools: Boolean = false,
    val facts: List<MemoryFact> = emptyList(),
    val recent: List<Exchange> = emptyList(),
)

/** "Remember that my home is …": stores one short fact for later conversations. */
object RememberTool {
    const val FUNCTION_NAME = "remember"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Save one short fact about the user to remember in later conversations: an address (home, " +
            "work), how they like to be addressed, a lasting preference (preferred music app or playlist, seat or " +
            "climate habits, how loud to be outside the car). Call it when the user asks you to remember " +
            "something, or after they agree to your offer to remember a preference. Never save something the " +
            "user has not asked for or agreed to, and nothing that is only true right now. Write it as one " +
            "plain sentence about the user, such as \"Home address: Al Khuwair, Muscat\". When the user tells you how " +
            "to greet them when they get in the car, start the sentence with \"When the driver gets in:\" so that it is " +
            "used only for that greeting.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "fact" to GeminiSchema(type = "STRING", description = "The fact, as one short plain sentence."),
            ),
            required = listOf("fact"),
        ),
    )

    fun fact(call: GeminiFunctionCall): String? =
        (call.args["fact"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    fun answer(outcome: AddOutcome): JsonObject = when (outcome) {
        is AddOutcome.Added -> buildJsonObject { put("status", "ok"); put("saved", outcome.fact.text) }
        is AddOutcome.AlreadyKnown -> buildJsonObject { put("status", "ok"); put("note", "Already remembered: ${outcome.fact.text}") }
        AddOutcome.Empty -> buildJsonObject { put("status", "error"); put("error", "There was nothing to remember.") }
        AddOutcome.Full -> buildJsonObject {
            put("status", "error")
            put("error", "Memory is full (${MemoryLimits.MAX_FACTS} items). Ask the user what to forget first.")
        }
    }

    fun refused(): JsonObject = buildJsonObject {
        put("status", "error")
        put("error", "Long-term memory is switched off in the settings.")
    }
}

/** "Forget my work address": removes the stored facts that mention it. */
object ForgetMemoryTool {
    const val FUNCTION_NAME = "forget_memory"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Forget something you were asked to remember. Pass a word or short phrase that appears in " +
            "the item to forget, such as \"work\" or \"home address\"; every remembered item containing it is " +
            "removed. Use when the user asks you to forget something.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "match" to GeminiSchema(
                    type = "STRING",
                    description = "A word or phrase (at least ${MemoryLimits.MIN_FORGET_MATCH_CHARS} letters) that appears in what to forget.",
                ),
            ),
            required = listOf("match"),
        ),
    )

    fun match(call: GeminiFunctionCall): String? =
        (call.args["match"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    fun answer(removed: Int): JsonObject = if (removed > 0) {
        buildJsonObject { put("status", "ok"); put("forgotten", removed) }
    } else {
        buildJsonObject { put("status", "error"); put("error", "Nothing remembered matched that.") }
    }

    fun refused(): JsonObject = RememberTool.refused()
}

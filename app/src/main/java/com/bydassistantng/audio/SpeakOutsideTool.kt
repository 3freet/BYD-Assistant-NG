package com.bydassistantng.audio

import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_MESSAGE_LENGTH = 400

private val volumeSchema = GeminiSchema(
    type = "STRING",
    description = "How loud it should be. Only when the user asks for a level (\"loudly\", \"quietly\", " +
        "\"shout\", \"softly\"); otherwise leave it out and the user's own setting is used.",
    enum = ExteriorVolume.entries.map { it.word },
)

/** The level the model asked for, or null (use the user's setting). */
private fun volumeOf(call: GeminiFunctionCall): ExteriorVolume? =
    ExteriorVolume.from((call.args["volume"] as? JsonPrimitive)?.content)

/**
 * "Tell the person outside…": the assistant's spoken reply is played by the exterior speaker instead of the
 * cabin speakers. The model gives the words; the tool only switches where the next reply is heard, so
 * the model then says them itself, in its own voice. Only offered when an [ExteriorSpeaker] exists.
 */
object SpeakOutsideTool {
    const val FUNCTION_NAME = "speak_outside"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Say something to people outside the car, through the car's exterior speaker instead of " +
            "the cabin speakers. Use when the user asks to tell, call out to, warn or greet someone outside " +
            "the car (\"tell him to wait\", \"say hello to my friend outside\"). Pass the exact words to say " +
            "as the message, in the language they should be said in. It can also imitate a sound with your " +
            "voice (an animal noise, a whistle) when the user asks for one that play_sound_outside does " +
            "not have: pass the sound itself as the message. After it succeeds, say the message aloud right " +
            "away and nothing else: only that is heard outside, and nobody in the cabin hears it.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "message" to GeminiSchema(
                    type = "STRING",
                    description = "The words to say to the person outside, as they should be spoken.",
                ),
                "volume" to volumeSchema,
            ),
            required = listOf("message"),
        ),
    )

    /** The words to say, or null when the model gave none. */
    fun message(call: GeminiFunctionCall): String? =
        (call.args["message"] as? JsonPrimitive)?.content?.trim()?.take(MAX_MESSAGE_LENGTH)?.takeIf { it.isNotEmpty() }

    /** The level the model asked for, or null when it did not ask for one. */
    fun volume(call: GeminiFunctionCall): ExteriorVolume? = volumeOf(call)

    /** The exterior speaker is on: the model's next words are heard outside, so it must say [message] now. */
    fun ready(message: String): JsonObject = buildJsonObject {
        put("status", "ok")
        put(
            "instruction",
            "The exterior speaker is on. Say this aloud now, exactly, with nothing before or after it: \"$message\"",
        )
    }

    fun refused(reason: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("error", "Could not speak outside the car: $reason.")
    }
}

/**
 * A ready-made sound effect played on the exterior speaker: a doorbell, a horn, a cat. Instant (no words to
 * wait for), and the cabin keeps hearing the assistant's own replies. The names come from the speaker that
 * can play them, so the model is only ever offered sounds that exist.
 */
object PlaySoundOutsideTool {
    const val FUNCTION_NAME = "play_sound_outside"

    fun declaration(effects: List<SoundEffect>) = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Play a sound effect on the car's exterior speaker, so people outside hear it and the " +
            "cabin does not. Use when the user asks for a sound outside the car (\"play a doorbell outside\", " +
            "\"honk\", \"meow on the external speaker\"). Sounds: " +
            effects.joinToString("; ") { "${it.name} (${it.description})" } + ". For a sound that is not in " +
            "the list, use speak_outside to imitate it with your voice instead. After it succeeds, confirm " +
            "in a few words.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "sound" to GeminiSchema(
                    type = "STRING",
                    description = "Which sound to play.",
                    enum = effects.map { it.name },
                ),
                "volume" to volumeSchema,
            ),
            required = listOf("sound"),
        ),
    )

    /** The effect asked for, or null when the name is not one of [effects]. */
    fun effect(call: GeminiFunctionCall, effects: List<SoundEffect>): SoundEffect? {
        val name = (call.args["sound"] as? JsonPrimitive)?.content?.trim().orEmpty()
        return effects.find { it.name.equals(name, ignoreCase = true) }
    }

    fun volume(call: GeminiFunctionCall): ExteriorVolume? = volumeOf(call)

    fun playing(effect: SoundEffect): JsonObject = buildJsonObject {
        put("status", "ok")
        put("sound", effect.name)
    }

    fun refused(reason: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("error", "Could not play a sound outside the car: $reason.")
    }
}

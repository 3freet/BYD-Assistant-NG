package com.bydassistantng.vehicle

import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema

/**
 * The registry's commands, folded into one Gemini function per family (`seat`, `window`, `dashcam`, …), so the model
 * is offered about fourteen functions instead of fifty-six. A call names the [setting] inside the family and carries
 * one `value`; it resolves to exactly the command it always was, so ids, car ids and the helper protocol are untouched.
 *
 * A command's family is the part of its id before the first dot and its setting is the rest (`seat.driver.heating`
 * is setting `driver.heating` of `seat`). An id without a dot is the family's own state (`trunk` is `trunk.state`).
 */
internal const val DEFAULT_SETTING = "state"

internal val VehicleCommand.family: String get() = id.substringBefore('.')

internal val VehicleCommand.setting: String get() = if ('.' in id) id.substringAfter('.') else DEFAULT_SETTING

/** What a call to a family function resolved to. */
sealed interface CommandLookup {
    data class Found(val command: VehicleCommand) : CommandLookup

    /** No family of this name (or none that is offered). */
    data object UnknownFunction : CommandLookup

    /** The family exists but the setting named is not one of its [settings] (or was left out of a family with several). */
    data class UnknownSetting(val settings: List<String>) : CommandLookup
}

/** One short line of what a family is, for the model; a family without an entry is described by its name. */
private val familyHints: Map<String, String> = mapOf(
    "ac" to "The car's air conditioner.",
    "window" to "The car's windows. Raise or up means toward closed; lower or down means toward open. A setting named " +
        "only after the window acts on it (open, close, stop, half, breath); one ending in .position moves it to a " +
        "percent closed (\"raise it to 100\" is closed, \"lower it to 70\" is 70 closed).",
    "sunroof" to "The sunroof: its state, or a percent open.",
    "sunshade" to "The sunroof's sunshade.",
    "audio" to "The car's sound system: volume, mute, and the Devialet sound options.",
    "trunk" to "The trunk (boot) lid.",
    "screen" to "The head unit's own screen (not the passenger screen).",
    "mirrors" to "The side mirror settings.",
    "ambient" to "The cabin's ambient light strip.",
    "fridge" to "The car's fridge.",
    "seat" to "The seats: heating, ventilation and massage, per seat.",
    "steeringWheel" to "The steering wheel.",
    "dashcam" to "The built-in dashcam.",
    "cabin" to "The cabin lights.",
)

private fun VehicleParameter.valuesText(): String = when (this) {
    is VehicleParameter.FixedEnum -> options.keys.joinToString("|")
    is VehicleParameter.Range -> "$min..$max" + (unit?.let { " $it" } ?: "")
}

/** The words a value may be, for an error the model can act on. */
internal fun VehicleCommand.valuesText(): String = parameter.valuesText()

/** The families among [commands], in the order the registry lists them. */
internal fun List<VehicleCommand>.byFamily(): Map<String, List<VehicleCommand>> = groupBy { it.family }

/**
 * The function for one family: a `setting` (one of the family's own names; left out when the family has only one) and
 * a `value` (an option word or a number, as listed for that setting). The values are listed per setting in the
 * description so the schema stays one shape for every family.
 */
internal fun familyDeclaration(family: String, commands: List<VehicleCommand>): GeminiFunctionDeclaration {
    val single = commands.size == 1
    val description = buildString {
        append(familyHints[family] ?: "The car's $family.")
        append(if (single) " " else " Settings:\n")
        commands.forEachIndexed { index, command ->
            if (!single) {
                append(command.setting).append(": ")
            }
            append(command.displayName).append(" (").append(command.valuesText()).append(')')
            if (index != commands.lastIndex) append('\n')
        }
    }
    val properties = buildMap {
        if (!single) {
            put("setting", GeminiSchema(type = "STRING", enum = commands.map { it.setting }))
        }
        put("value", GeminiSchema(type = "STRING"))
    }
    return GeminiFunctionDeclaration(
        name = family,
        description = description,
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = properties,
            required = properties.keys.toList(),
        ),
    )
}

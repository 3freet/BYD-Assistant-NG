package com.bydassistantng.vehicle

import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Switch to sand mode": asks the car for a drive mode and reports the mode the car says it is really in.
 * Only offered when a [DriveModeSwitch] exists (debug builds) and vehicle control is armed.
 */
object SetDriveModeTool {
    const val FUNCTION_NAME = "set_drive_mode"

    /** Every mode the car's own screen can be asked for: what a car on the Leopard's route is offered. */
    val declaration = declarationFor(DriveMode.entries)

    /**
     * The tool for a car that can be asked for [modes] ([DriveModeControl.modesOf]): the model is told only those, so a car that has
     * Eco, Normal and Sport (the classic operation mode) is never offered "sand".
     */
    fun declarationFor(modes: List<DriveMode>): GeminiFunctionDeclaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = if (modes.size == DriveMode.entries.size) {
            "Change the car's drive mode (driving mode). Use when the user asks to switch to or enter " +
                "one: snow, sand, mud, mountain, rock or auto (the off-road modes), or normal, eco or sport; use " +
                "exit to leave an off-road mode. Only when the user asks, never to try something out. The car " +
                "decides: it only changes when its conditions are met, and the answer says which mode it is " +
                "really in afterwards, so tell the user that in a few words. When the user asks for " +
                "more than the mode (\"sand mode and play some music\"), call this and the other function in the " +
                "same turn. Wade, warp, custom and traction modes are not available: say so plainly."
        } else {
            "Change the car's drive mode (driving mode). Use when the user asks to switch to ${modes.joinToString(", ") { it.word }}. " +
                "Only when the user asks, never to try something out. The car " +
                "decides, and which of these modes it has is its to say: it only changes when it has the mode and its " +
                "conditions are met, and the answer says which mode it is " +
                "really in afterwards, so tell the user that in a few words. When the user asks for " +
                "more than the mode (\"sport mode and play some music\"), call this and the other function in the " +
                "same turn. Any other mode cannot be asked for here: say so plainly."
        },
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "mode" to GeminiSchema(
                    type = "STRING",
                    description = "The drive mode to switch to.",
                    enum = modes.map { it.word },
                ),
            ),
            required = listOf("mode"),
        ),
    )

    /** The mode the model asked for, or null when it named none the car can be asked for. */
    fun mode(call: GeminiFunctionCall): DriveMode? = DriveMode.from((call.args["mode"] as? JsonPrimitive)?.content)

    /**
     * What goes back to the model for [outcome]. Every answer says what to do next: on the car the model
     * sometimes asked again straight after a success (up to five times in a row), each time costing a trip to
     * the car and delaying the spoken confirmation.
     */
    fun answer(outcome: DriveModeOutcome): JsonObject = when (outcome) {
        is DriveModeOutcome.Changed -> buildJsonObject {
            put("status", "ok")
            put("drive_mode_now", outcome.now)
            outcome.before?.let { put("drive_mode_before", it) }
            put(
                "instruction",
                "Done. Tell the user in a few words that the car is now in ${outcome.now} mode. " +
                    "Do not call this function again for this request.",
            )
        }
        is DriveModeOutcome.Unchanged -> buildJsonObject {
            put("status", "error")
            put(
                "error",
                "The car did not switch: ${outcome.detail}." + (outcome.current?.let { " It is in $it mode." } ?: ""),
            )
            put("instruction", "Do not try again. Tell the user in a few words what the car did.")
        }
        is DriveModeOutcome.Refused -> refused(outcome.reason)
    }

    /** The answer to a request the car carried out moments ago, given without asking the car again. */
    fun alreadyDone(outcome: DriveModeOutcome.Changed): JsonObject = buildJsonObject {
        put("status", "ok")
        put("drive_mode_now", outcome.now)
        put("already_done", true)
        put(
            "instruction",
            "That was already done a moment ago: the car is in ${outcome.now} mode. " +
                "Do not call this function again; just tell the user.",
        )
    }

    fun refused(reason: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("error", "Could not change the drive mode: $reason.")
        put("instruction", "Do not try again. Tell the user in a few words why it did not happen.")
    }
}

/**
 * Remembers the drive mode request the car just carried out, so that the same request arriving again within
 * moments (the model asking twice) is answered from the first result instead of starting another helper.
 * Only a confirmed change is remembered; anything else clears it, so a request that failed is never "repeated".
 */
class DriveModeRepeatGuard(private val windowMs: Long = WINDOW_MS) {
    private var requested: DriveMode? = null
    private var outcome: DriveModeOutcome.Changed? = null
    private var atMs = 0L

    /** The result already given for [mode] a moment ago, or null when this is a new request. */
    fun repeatOf(mode: DriveMode, nowMs: Long): DriveModeOutcome.Changed? =
        outcome?.takeIf { requested == mode && nowMs - atMs in 0..windowMs }

    fun record(mode: DriveMode, result: DriveModeOutcome, nowMs: Long) {
        if (result is DriveModeOutcome.Changed) {
            requested = mode
            outcome = result
            atMs = nowMs
        } else {
            requested = null
            outcome = null
        }
    }

    companion object {
        // Longer than the model's repeats (about two seconds apart on the car, five in a row at worst; they come
        // faster once answered from here), shorter than a person asking again after changing the mode by hand.
        const val WINDOW_MS = 6_000L
    }
}

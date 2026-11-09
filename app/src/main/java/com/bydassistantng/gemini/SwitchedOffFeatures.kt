package com.bydassistantng.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The things the assistant does only once the user has switched them on in Settings.
 *
 * Asked for one that was off, the assistant had nothing to go on and answered with something vague ("the system does not allow
 * it"), which tells the user nothing about what to do. So it is told, with its instructions, which of these features this car has
 * and the user has left off, and where each is switched on ([SwitchedOffFeatures.promptSection]); and the answer a tool gives when
 * it is called for one that is off says the same ([SwitchedOffFeatures.toolAnswer]).
 *
 * The rule is the same on every car and for every way a feature reaches the car (the drive mode has two): a feature that this car
 * and build **have**, and whose switch is **off**, is named with the place to switch it on; a feature this car does not have at all
 * is said not to be offered on it, since nothing could be switched on.
 */
enum class SwitchableFeature(
    /** What the feature does, in the words a request for it can be recognised by (said to the model, in English). */
    val covers: String,
    /** The feature also needs the vehicle control switch to be on: it is one of the ways of operating the car. */
    val needsVehicleControl: Boolean = false,
) {
    VEHICLE_CONTROL(
        "operating the car: its climate, windows, sunroof, seats, steering-wheel heating, lights, fridge and mirrors, the dashcam and the " +
            "passenger screen, and reading its tyre pressure, air quality and temperatures (the drive mode, the exterior speaker and the " +
            "instrument cluster need it too)",
    ),
    EXTERIOR_SPEAKER("speaking or playing a sound through the car's exterior speaker, to someone outside", needsVehicleControl = true),
    DRIVE_MODES("changing the car's drive mode, such as eco, sport, snow or sand", needsVehicleControl = true),
    CLUSTER_SCREEN("putting an app or a video on the instrument cluster behind the steering wheel, and taking it off", needsVehicleControl = true),
    WEB_SEARCH("looking things up on the web: news, weather, sports results, prices, opening hours"),
    LONG_TERM_MEMORY("remembering things for later, such as an address or a preference, and forgetting them"),
}

/** What a conversation knows of one feature: whether this car and build have it at all ([present]) and whether its switch is on ([on]). */
data class FeatureState(val present: Boolean, val on: Boolean)

/** A feature that is present and off, with where the user switches it on, written as the screens write it. */
data class SwitchedOff(val feature: SwitchableFeature, val path: String)

object SwitchedOffFeatures {
    /** The state of every feature, from what a conversation knows of the car and of the settings. */
    fun states(
        vehicleControlOn: Boolean,
        exteriorSpeaker: FeatureState,
        driveModes: FeatureState,
        cluster: FeatureState,
        webSearchOn: Boolean,
        longTermMemoryOn: Boolean,
    ): Map<SwitchableFeature, FeatureState> = mapOf(
        SwitchableFeature.VEHICLE_CONTROL to FeatureState(present = true, on = vehicleControlOn),
        SwitchableFeature.EXTERIOR_SPEAKER to exteriorSpeaker,
        SwitchableFeature.DRIVE_MODES to driveModes,
        SwitchableFeature.CLUSTER_SCREEN to cluster,
        SwitchableFeature.WEB_SEARCH to FeatureState(present = true, on = webSearchOn),
        SwitchableFeature.LONG_TERM_MEMORY to FeatureState(present = true, on = longTermMemoryOn),
    )

    /** The features this car has and the user has left off, each with [pathOf] its place in Settings, in the order of [SwitchableFeature]. */
    fun switchedOff(states: Map<SwitchableFeature, FeatureState>, pathOf: (SwitchableFeature) -> String): List<SwitchedOff> =
        SwitchableFeature.entries.mapNotNull { feature ->
            val state = states[feature] ?: return@mapNotNull null
            if (state.present && !state.on) SwitchedOff(feature, pathOf(feature)) else null
        }

    /** The features this car does not have at all: there is nothing to switch on. */
    fun absent(states: Map<SwitchableFeature, FeatureState>): List<SwitchableFeature> =
        SwitchableFeature.entries.filter { states[it]?.present == false }

    /**
     * What is added to the assistant's instructions: which features are off and where to switch each on, and which the car does not
     * have; null when there is nothing to say. The assistant is told to answer a request for one of them in a sentence that says so,
     * not to try and not to blame the system or the car.
     */
    fun promptSection(off: List<SwitchedOff>, absent: List<SwitchableFeature>): String? {
        if (off.isEmpty() && absent.isEmpty()) return null
        return buildString {
            if (off.isNotEmpty()) {
                append(" Some of what you can do is switched off in the app's Settings, and only the user can switch it on. ")
                append("If the user asks for any of it, in any wording, do not try, and do not say that you cannot or that the system ")
                append("or the car does not allow it: say in one short sentence that it is switched off and where to switch it on, ")
                append("and that it works from the next conversation. Switched off now: ")
                append(
                    off.joinToString("; ") { item ->
                        item.feature.covers +
                            (if (item.feature.needsVehicleControl) " (it also needs vehicle control on)" else "") +
                            " - switch it on in: ${item.path}"
                    },
                )
                append('.')
            }
            if (absent.isNotEmpty()) {
                append(" This car does not offer the following to the app at all, and no setting changes that: ")
                append(absent.joinToString("; ") { it.covers })
                append(". If the user asks for one of them, say in one short sentence that this car does not offer it.")
            }
        }
    }

    /** The answer to a tool call for a feature that is switched off (a call the model was not given the tool for, but made): where to switch it on. */
    fun toolAnswer(off: SwitchedOff): JsonObject = buildJsonObject {
        put("status", "error")
        put("error", "This is switched off in the app's Settings: ${off.path}.")
        put(
            "instruction",
            "Tell the user in one short sentence that it is switched off, that they can switch it on in ${off.path}, and that it works " +
                "from the next conversation. Do not say that the system or the car does not allow it.",
        )
    }

    /** The answer to a tool call for a feature this car does not have. */
    fun unavailableAnswer(feature: SwitchableFeature): JsonObject = buildJsonObject {
        put("status", "error")
        put("error", "This car does not offer ${feature.covers} to the app.")
        put("instruction", "Tell the user in one short sentence that this car does not offer it. No setting changes that.")
    }
}

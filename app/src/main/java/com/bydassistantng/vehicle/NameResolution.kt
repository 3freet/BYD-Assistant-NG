package com.bydassistantng.vehicle

/**
 * Finds a command's numbers in the car's own table of features, by name, so that a command is sent to what this car calls
 * the control and not to what another car, or the car this registry was verified on, numbers that way.
 *
 * Every number a registry entry holds has the name the car's table gives it (`featureName`, `stateFeatureName`, and the same on
 * `requires` and on each override). Given the car's table ([CarFeatureTable]) an entry becomes one of two things:
 *  - the entry with the car's numbers in place of its own (unchanged on the car it was verified on, where every name has the
 *    number it already has), or
 *  - **withheld**, with the names the car does not have: nothing is offered for it and nothing is sent, because a number the
 *    car does not name is not known to be this control on this car, and it may be another feature's.
 *
 * Which missing names withhold a command: the control's, the precondition's and any override's (the command cannot be
 * carried out as it is meant), and the state that reports the result only where the command insists on that reading
 * ([CommandEntryDto.requiresReadback]). A command whose state the car does not name is otherwise still sent, and the answer is
 * "sent" rather than a verdict from a number that may mean something else.
 */
internal object NameResolution {
    sealed interface Outcome {
        /** The entry as this car numbers it. */
        data class Use(val entry: CommandEntryDto) : Outcome

        /** The car's table lacks these names: the command is not available on this car. */
        data class Withhold(val missing: List<String>) : Outcome
    }

    fun resolve(entry: CommandEntryDto, table: Map<String, Int>): Outcome {
        val generic = entry.invocation.type == "genericFeatureSet"
        // The A/C, the dashcam and the volume use no feature numbers of the car's table.
        if (!generic && entry.invocation.type != "namedMethod") return Outcome.Use(entry)

        val missing = ArrayList<String>()
        fun number(name: String?, fallback: Int): Int {
            if (name == null) return fallback
            return table[name] ?: fallback.also { missing += name }
        }

        var result = entry
        if (generic) {
            result = result.copy(
                featureId = entry.featureId?.let { number(entry.featureName, it) },
                requires = entry.requires?.let { it.copy(featureId = number(it.featureName, it.featureId)) },
                overrides = entry.overrides.map { it.copy(featureId = number(it.featureName, it.featureId)) },
            )
        }
        val stateName = entry.stateFeatureName
        if (entry.stateFeatureId != null && stateName != null) {
            val state = table[stateName]
            result = when {
                state != null -> result.copy(stateFeatureId = state)
                entry.requiresReadback -> result.also { missing += stateName }
                else -> result.copy(stateFeatureId = null, stateFeatureName = null)
            }
        }
        return if (missing.isEmpty()) Outcome.Use(result) else Outcome.Withhold(missing.distinct())
    }
}

/**
 * The number this car gives a feature the app reads outside the command registry (the headrest sound, who sits in the driver's
 * seat, the cabin temperature). Where the car's table is known, its own number for [of]'s name; where it could not be read, the
 * number the feature was verified with (what the app always used); where the table lacks the name, none.
 */
internal object CarNumbers {
    fun of(name: String, verified: Int): Int? {
        val table = VehicleCommandRegistry.carTable ?: return verified
        return table[name]
    }
}

package com.bydassistantng.vehicle

/**
 * Compares the feature numbers the app uses with what the car's own table of features (`BYDAutoFeatureIds`) says, **by
 * name**. A name (`Bodywork.BODYWORK_LF_WINDOW_CTRL_SET`) is what stays the same from car to car; the number it carries
 * may not, and a number that is right on one car can be another feature, or nothing, on the next.
 *
 * For every number the app uses (the registry's and the few it uses elsewhere, see [OtherFeatures]) it says whether the car's
 * table gives the name that number ([Verdict.SAME]), another number ([Verdict.DIFFERENT], with the car's own) or no such name
 * ([Verdict.ABSENT]). What the app does about it is [NameResolution]'s: a registry command takes the car's number, or is
 * withheld where the car lacks a name it needs; the reads outside the registry take the car's number too ([CarNumbers]), and
 * the features that write to the car and were verified on one car only ([OtherFeatures]' last three) are used only where the
 * car's number is the verified one. The result is written to the log, so that a car's numbers, and what the app made of them,
 * can be read from its owner's log.
 */
internal object FeatureNameCheck {
    /** At most this many entries are written for each kind of difference, so that a car that differs everywhere stays one readable line. */
    private const val MAX_LISTED = 150

    const val CONTROL = "ctl"
    const val STATE = "state"
    const val REQUIRES = "requires"
    const val OVERRIDE = "override"

    /** One number the app sends to the car or reads from it, and the name the car's table is expected to give it. */
    data class Reference(
        /** The command it belongs to, or what an outside feature is for. */
        val owner: String,
        /** Which of the command's numbers: [CONTROL], [STATE], [REQUIRES] or [OVERRIDE]; empty for an outside feature. */
        val part: String,
        /** `Group.NAME` in the car's table. */
        val name: String,
        /** The number the app uses for it. */
        val number: Int,
    )

    enum class Verdict { SAME, DIFFERENT, ABSENT }

    data class Result(val reference: Reference, val carNumber: Int?, val verdict: Verdict)

    /** The numbers [command] sends to the car or reads from it. The A/C, dashcam and volume commands use none of the car's feature numbers. */
    fun numbersUsedBy(command: VehicleCommand): List<Int> {
        val numbers = when (command.invocation) {
            VehicleInvocation.GenericFeatureSet ->
                listOfNotNull(command.featureId, command.stateFeatureId, command.requires?.featureId) + command.overrides.map { it.featureId }
            is VehicleInvocation.NamedMethod -> listOfNotNull(command.stateFeatureId)
            else -> emptyList()
        }
        return numbers.distinct()
    }

    /** The numbers of [command] that have a name, each with it. */
    fun referencesOf(command: VehicleCommand): List<Reference> {
        val parts = buildList {
            when (command.invocation) {
                VehicleInvocation.GenericFeatureSet -> {
                    add(Triple(CONTROL, command.featureName, command.featureId))
                    add(Triple(STATE, command.stateFeatureName, command.stateFeatureId))
                    command.requires?.let { add(Triple(REQUIRES, it.featureName, it.featureId)) }
                    command.overrides.forEach { add(Triple(OVERRIDE, it.featureName, it.featureId)) }
                }
                is VehicleInvocation.NamedMethod -> add(Triple(STATE, command.stateFeatureName, command.stateFeatureId))
                else -> Unit
            }
        }
        return parts.mapNotNull { (part, name, number) ->
            if (name != null && number != null) Reference(command.id, part, name, number) else null
        }
    }

    /** Every number the app uses: the registry's commands (with their own numbers) and the features it reads or writes outside it. */
    fun references(): List<Reference> =
        VehicleCommandRegistry.commandsFor(DiLinkPlatform.UNKNOWN).known.flatMap { referencesOf(it) } +
            OtherFeatures.all.map { Reference(it.label, "", it.name, it.number) }

    /** What the car's table ([carTable]: `Group.NAME` to number) says about each reference. */
    fun check(references: List<Reference>, carTable: Map<String, Int>): List<Result> = references.map { reference ->
        val car = carTable[reference.name]
        Result(reference, car, if (car == null) Verdict.ABSENT else if (car == reference.number) Verdict.SAME else Verdict.DIFFERENT)
    }

    /**
     * The result as one line for the log: `names=<agree>/<checked>`, then every name where the car's number differs
     * (`name=<car's number>/<ours>(command)`: the car's is the one used), every name the car does not have (`name(command)`),
     * and the commands this car cannot do because of it (`withheld=`, `none` for none). A name is counted once however many
     * commands use it.
     */
    fun summary(results: List<Result>, withheld: Collection<String> = emptyList()): String {
        val byName = LinkedHashMap<String, Result>()
        for (result in results) byName.putIfAbsent(result.reference.name, result)
        val all = byName.values.toList()
        val differ = all.filter { it.verdict == Verdict.DIFFERENT }.map(::entry)
        val absent = all.filter { it.verdict == Verdict.ABSENT }.map(::entry)
        return buildString {
            append("names=").append(all.count { it.verdict == Verdict.SAME }).append('/').append(all.size)
            if (differ.isNotEmpty()) append(" differ=").append(listed(differ))
            if (absent.isNotEmpty()) append(" absent=").append(listed(absent))
            append(" withheld=").append(listed(withheld.sorted()).ifEmpty { "none" })
        }
    }

    /** Whether any window command's name is not the car's own, which is when the car's window features are worth listing. */
    fun windowsDiffer(results: List<Result>): Boolean =
        results.any { it.reference.owner.startsWith("window.") && it.verdict != Verdict.SAME }

    private fun entry(result: Result): String {
        val reference = result.reference
        return if (result.verdict == Verdict.DIFFERENT) {
            "${reference.name}=${result.carNumber}/${reference.number}(${reference.owner})"
        } else {
            "${reference.name}(${reference.owner})"
        }
    }

    private fun listed(entries: List<String>): String =
        entries.take(MAX_LISTED).joinToString(",") + if (entries.size > MAX_LISTED) ",+${entries.size - MAX_LISTED} more" else ""
}

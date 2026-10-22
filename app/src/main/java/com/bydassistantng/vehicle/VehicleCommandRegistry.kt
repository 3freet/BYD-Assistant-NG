package com.bydassistantng.vehicle

import android.util.Log
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

private const val TAG = "VehicleCommandRegistry"

/**
 * Known BYD vehicle controls, loaded from `vehicle_commands.json` (a classpath resource, so the exact
 * same file works in plain JUnit tests and on a real device). There are no per-command phrase lists:
 * Gemini function calling does the matching (see [functionDeclarations], one function per family of
 * commands), in any language it understands.
 *
 * Loading fails **per-entry, not for the whole registry**: a malformed/blocked-domain/duplicate
 * entry is dropped and logged instead of thrown, since one bad hand-edit to the JSON must never
 * brick the assistant's whole conversational path, not just vehicle control.
 *
 * Safety is layered, not singular: a blocked-domain command is filtered out here (never reaches
 * [known]), so it can never be declared to Gemini as a callable function in the first place —
 * strictly stronger than the old app, where a local matcher could theoretically be handed a bad
 * entry. [VehicleSafety.assertDispatchAllowed] remains the hard dispatch-time gate on top of that.
 */
object VehicleCommandRegistry {
    private const val RESOURCE_NAME = "vehicle_commands.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    internal val entries: List<CommandEntryDto> = loadEntriesSafely()

    val known: List<VehicleCommand> = entries.toSafeVehicleCommands()

    private val families: Map<String, List<VehicleCommand>> = known.byFamily()

    fun byId(id: String): VehicleCommand? = known.find { it.id == id }

    /**
     * One Gemini function per family of known (non-blocked) commands, each with a `setting` and a `value` (see
     * [familyDeclaration]). With [carLink] false only the commands that are plain Android calls ([needsCarLink]
     * false) are listed, and a family with none of them is not offered at all.
     */
    fun functionDeclarations(carLink: Boolean = true): List<GeminiFunctionDeclaration> =
        known.filter { carLink || !it.needsCarLink }.byFamily().map { (family, commands) -> familyDeclaration(family, commands) }

    /** The names of the functions [functionDeclarations] offers. */
    fun functionNames(carLink: Boolean = true): List<String> = functionDeclarations(carLink).map { it.name }

    /**
     * The command a call to a family function means. A [setting] names it among the family's own; left out, it is
     * the family's only setting, or its only one offered with [carLink] off (so the audio family is just the volume
     * without the link). A command that exists but is not offered with [carLink] off still resolves: the
     * dispatcher's own gate decides what happens to it, not this lookup.
     */
    fun resolve(functionName: String, setting: String?, carLink: Boolean = true): CommandLookup {
        val family = families[functionName] ?: return CommandLookup.UnknownFunction
        val wanted = setting?.trim().orEmpty()
        if (wanted.isEmpty()) {
            val offered = family.filter { carLink || !it.needsCarLink }
            return (family.singleOrNull() ?: offered.singleOrNull())?.let { CommandLookup.Found(it) }
                ?: CommandLookup.UnknownSetting(offered.map { it.setting })
        }
        val match = family.firstOrNull { it.setting.equals(wanted, ignoreCase = true) }
        return match?.let { CommandLookup.Found(it) }
            ?: CommandLookup.UnknownSetting(family.filter { carLink || !it.needsCarLink }.map { it.setting })
    }

    /** Resolves the "value" argument from a Gemini function call into the concrete int this
     * command's [VehicleController] expects — an enum option name for [VehicleParameter.FixedEnum],
     * or a clamped integer for [VehicleParameter.Range] (defensive clamping, since nothing
     * guarantees Gemini strictly respects a schema's numeric bounds). */
    fun resolveValue(command: VehicleCommand, rawValue: String?): Int? = when (val param = command.parameter) {
        is VehicleParameter.FixedEnum -> rawValue?.trim()?.let { word -> param.options[word] ?: param.options.entries.firstOrNull { it.key.equals(word, ignoreCase = true) }?.value }
        is VehicleParameter.Range -> rawValue?.let(::wholeNumber)?.coerceIn(param.min, param.max)
    }

    /** "22" or a JSON number that came through as "22.0"; anything with a fraction is not a whole number. */
    private fun wholeNumber(text: String): Int? {
        text.trim().toIntOrNull()?.let { return it }
        val asDouble = text.trim().toDoubleOrNull() ?: return null
        return asDouble.takeIf { it == Math.rint(it) && Math.abs(it) < Int.MAX_VALUE }?.toInt()
    }

    private fun loadEntriesSafely(): List<CommandEntryDto> = try {
        val stream = javaClass.classLoader?.getResourceAsStream(RESOURCE_NAME)
            ?: error("$RESOURCE_NAME not found on the classpath")
        stream.use { json.decodeFromStream<List<CommandEntryDto>>(it) }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load $RESOURCE_NAME — vehicle control is unavailable this session", e)
        emptyList()
    }
}

// ── JSON DTOs and mapping to the sealed domain types ────────────────────────────────────────
// Loose/nullable DTOs (rather than polymorphic deserialization onto the sealed types directly) so
// a malformed or unrecognized "type" discriminator is easy to catch and drop per-entry below.

@Serializable
internal data class CommandEntryDto(
    val id: String,
    val domain: String,
    val displayName: String,
    val label: String? = null,
    val labelAr: String? = null,
    val labelRu: String? = null,
    val deviceType: Int? = null,
    val featureId: Int? = null,
    val invocation: InvocationDto,
    val parameter: ParameterDto,
    val stateFeatureId: Int? = null,
    val valueOffset: Int = 0,
    val inverted: Boolean = false,
    val stateOffset: Int = 0,
    val stateTolerance: Int = 0,
    val gradual: Boolean = false,
    val requires: RequiresDto? = null,
    val overrides: List<OverrideDto> = emptyList(),
)

@Serializable
internal data class OverrideDto(val whenValue: Int, val featureId: Int, val sendValue: Int)

@Serializable
internal data class RequiresDto(val featureId: Int, val equals: Int, val message: String)

@Serializable
internal data class InvocationDto(
    val type: String,
    // namedMethod
    val deviceClass: String? = null,
    val methodName: String? = null,
    val paramTypes: List<String>? = null,
    val argsTemplate: List<Int?>? = null,
    // acBinderProperty
    val subServiceKey: String? = null,
    val interfaceDescriptor: String? = null,
    val area: Int? = null,
    val propertyId: Int? = null,
    // binderCommand
    val serviceName: String? = null,
    val transactionCode: Int? = null,
    val commandCode: Int? = null,
    val successCodes: List<Int>? = null,
    val failureMessages: Map<String, String>? = null,
    // streamVolume
    val stream: Int? = null,
)

@Serializable
internal data class ParameterDto(
    val type: String,
    val options: Map<String, Int>? = null, // fixedEnum
    val min: Int? = null, // range
    val max: Int? = null,
    val unit: String? = null,
)

/** Maps every entry, dropping (and loudly logging) anything that fails to parse, names a blocked
 * domain, or duplicates an id already seen — never throws. */
internal fun List<CommandEntryDto>.toSafeVehicleCommands(): List<VehicleCommand> {
    val parsed = mapNotNull { entry ->
        try {
            entry.toVehicleCommand()
        } catch (e: Exception) {
            Log.e(TAG, "Dropping malformed vehicle command '${entry.id}': ${e.message}")
            null
        }
    }

    val (safe, blocked) = parsed.partition { !it.domain.isBlocked }
    if (blocked.isNotEmpty()) {
        Log.e(TAG, "Dropping blocked-domain commands that should never have been in the registry: ${blocked.map { it.id }}")
    }

    val seenIds = HashSet<String>()
    val deduped = safe.filter { seenIds.add(it.id) }
    if (deduped.size != safe.size) {
        Log.e(TAG, "Dropped duplicate command ids: ${safe.map { it.id }.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }

    return deduped
}

internal fun CommandEntryDto.toVehicleCommand(): VehicleCommand {
    val domainEnum = VehicleDomain.entries.find { it.name == domain }
        ?: error("Unknown vehicle domain '$domain' for command '$id' — refusing to load")
    return VehicleCommand(
        id = id,
        domain = domainEnum,
        deviceType = deviceType,
        featureId = featureId,
        displayName = displayName,
        label = label,
        labelAr = labelAr,
        labelRu = labelRu,
        invocation = invocation.toVehicleInvocation(id),
        parameter = parameter.toVehicleParameter(id),
        stateFeatureId = stateFeatureId,
        valueOffset = valueOffset,
        inverted = inverted,
        stateOffset = stateOffset,
        stateTolerance = stateTolerance,
        gradual = gradual,
        requires = requires?.let { StateRequirement(it.featureId, it.equals, it.message) },
        overrides = overrides.map { ValueOverride(it.whenValue, it.featureId, it.sendValue) },
    )
}

private fun InvocationDto.toVehicleInvocation(commandId: String): VehicleInvocation = when (type) {
    "genericFeatureSet" -> VehicleInvocation.GenericFeatureSet
    "namedMethod" -> VehicleInvocation.NamedMethod(
        deviceClass = deviceClass ?: error("namedMethod invocation for '$commandId' missing deviceClass"),
        methodName = methodName ?: error("namedMethod invocation for '$commandId' missing methodName"),
        paramTypes = paramTypes ?: error("namedMethod invocation for '$commandId' missing paramTypes"),
        argsTemplate = argsTemplate ?: error("namedMethod invocation for '$commandId' missing argsTemplate"),
    )
    "acBinderProperty" -> VehicleInvocation.AcBinderProperty(
        subServiceKey = subServiceKey ?: error("acBinderProperty invocation for '$commandId' missing subServiceKey"),
        interfaceDescriptor = interfaceDescriptor ?: error("acBinderProperty invocation for '$commandId' missing interfaceDescriptor"),
        propertyId = propertyId ?: error("acBinderProperty invocation for '$commandId' missing propertyId"),
        area = area ?: error("acBinderProperty invocation for '$commandId' missing area"),
    )
    "binderCommand" -> VehicleInvocation.BinderCommand(
        serviceName = serviceName ?: error("binderCommand invocation for '$commandId' missing serviceName"),
        interfaceDescriptor = interfaceDescriptor ?: error("binderCommand invocation for '$commandId' missing interfaceDescriptor"),
        transactionCode = transactionCode ?: error("binderCommand invocation for '$commandId' missing transactionCode"),
        commandCode = commandCode ?: error("binderCommand invocation for '$commandId' missing commandCode"),
        successCodes = (successCodes ?: error("binderCommand invocation for '$commandId' missing successCodes")).toSet(),
        failureMessages = failureMessages.orEmpty().mapKeys { (code, _) ->
            code.toIntOrNull() ?: error("binderCommand failure code '$code' for '$commandId' is not an integer")
        },
    )
    "streamVolume" -> VehicleInvocation.AudioStreamVolume(
        stream = stream ?: error("streamVolume invocation for '$commandId' missing stream"),
    )
    else -> error("Unknown invocation type '$type' for command '$commandId' — refusing to load")
}

private fun ParameterDto.toVehicleParameter(commandId: String): VehicleParameter = when (type) {
    "fixedEnum" -> VehicleParameter.FixedEnum(options ?: error("fixedEnum parameter for '$commandId' missing options"))
    "range" -> VehicleParameter.Range(
        min = min ?: error("range parameter for '$commandId' missing min"),
        max = max ?: error("range parameter for '$commandId' missing max"),
        unit = unit,
    )
    else -> error("Unknown parameter type '$type' for command '$commandId' — refusing to load")
}

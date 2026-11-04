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
 * The numbers in an entry are those verified on a DiLink 5 car, and each has the name the car's own table of features gives
 * it. [known] is the list for this car: where the car's table has been read ([useCarTable], by the vehicle helper) every
 * command is looked up in it by name ([NameResolution]), takes the car's numbers, and is left out where the car lacks a name it
 * needs; before the table is read it is the whole registry with its own numbers, as it always was. The app, which does not read
 * the table itself, takes the ids of the commands the helper found the car cannot do ([useReportedUnavailable]). The
 * [platform] only decides how careful to be (see [CommandEntryDto.requiresReadbackElsewhere]).
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

    /** The platform this car runs. Set once it is known (see [PlatformState]); every list below follows it. */
    @Volatile
    var platform: DiLinkPlatform = DiLinkPlatform.UNKNOWN
        set(value) {
            field = value
            rebuild()
        }

    /** The car's own table of features, in the vehicle helper once it has read it; null in the app, and where it cannot be read. */
    @Volatile
    internal var carTable: Map<String, Int>? = null
        private set

    private var reportedUnavailable: Set<String> = emptySet()

    /** How the car's climate system is reached, in the vehicle helper once it has looked; null in the app, which does not. */
    @Volatile
    internal var acRoute: AcRoute? = null
        private set

    @Volatile
    private var current: CarCommands = CarCommands(entries, DiLinkPlatform.UNKNOWN)

    /** The vehicle helper has read the car's table (null where it could not): the commands follow it from here on. */
    @Synchronized
    internal fun useCarTable(table: Map<String, Int>?) {
        carTable = table
        rebuild()
    }

    /** The vehicle helper has found how the car's climate system is reached: the A/C commands follow it from here on. */
    @Synchronized
    internal fun useAcRoute(route: AcRoute?) {
        acRoute = route
        rebuild()
    }

    /** The app is told by the helper which commands this car cannot do, since only the helper reads the car's table. */
    @Synchronized
    fun useReportedUnavailable(ids: Set<String>) {
        reportedUnavailable = ids
        rebuild()
    }

    @Synchronized
    private fun rebuild() {
        current = CarCommands(entries, platform, carTable, reportedUnavailable, acRoute)
    }

    /** The commands of this car. */
    val known: List<VehicleCommand> get() = current.known

    /** What this car cannot do: command id to the names its table lacks (empty where only the helper's word is known). */
    val unavailable: Map<String, List<String>> get() = current.missingNames

    /**
     * The commands of a car on [platform] with [table] (null: the registry's own numbers) and [acRoute] (null: the one the A/C
     * commands were verified on), whichever car this is.
     */
    internal fun commandsFor(platform: DiLinkPlatform, table: Map<String, Int>? = null, acRoute: AcRoute? = null): CarCommands =
        CarCommands(entries, platform, table, emptySet(), acRoute)

    /** The names the A/C commands read in the car's table when they go through the climate device (see [AcRoute.DEVICE]). */
    internal fun deviceRouteNames(): List<String> =
        entries.mapNotNull { it.withoutAcService }.flatMap { listOfNotNull(it.stateFeatureName, it.requires?.featureName) }.distinct()

    private val families: Map<String, List<VehicleCommand>> get() = current.families

    fun byId(id: String): VehicleCommand? = known.find { it.id == id }

    /** Whether [id] is a command the registry has at all (a blocked one is not), whether or not this car can do it. */
    fun isCommand(id: String): Boolean = commandsFor(DiLinkPlatform.UNKNOWN).known.any { it.id == id }

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

/**
 * The registry as one car sees it: the commands it can do, with the car's own numbers where its [table] is known, and the
 * ones it cannot. [unavailable] are ids the helper reported (the app does not read the table itself). The A/C commands take
 * the route the car has ([acRoute], see [AcRoute]; null is the one they were verified on).
 */
class CarCommands internal constructor(
    entries: List<CommandEntryDto>,
    val platform: DiLinkPlatform,
    table: Map<String, Int>? = null,
    unavailable: Set<String> = emptySet(),
    acRoute: AcRoute? = null,
) {
    /** Each command this car cannot do, with the names its table lacks (none where only the helper's word is known). */
    val missingNames: Map<String, List<String>>

    val known: List<VehicleCommand>

    init {
        val missing = LinkedHashMap<String, List<String>>()
        val usable = entries.mapNotNull { entry ->
            val here = entry.copy(requiresReadback = entry.requiresReadbackElsewhere && platform != DiLinkPlatform.DILINK_5)
            val routed = here.forAcRoute(acRoute)
            when {
                entry.id in unavailable -> null.also { missing[entry.id] = emptyList() }
                // The climate system cannot be reached on this car: the name of the service stands for what is missing.
                routed == null -> null.also { missing[entry.id] = listOf(ReflectionVehicleController.AC_SERVICE_NAME) }
                table == null -> routed
                else -> when (val outcome = NameResolution.resolve(routed, table)) {
                    is NameResolution.Outcome.Use -> outcome.entry
                    is NameResolution.Outcome.Withhold -> null.also { missing[entry.id] = outcome.missing }
                }
            }
        }
        known = usable.toSafeVehicleCommands()
        missingNames = missing
    }

    internal val families: Map<String, List<VehicleCommand>> = known.byFamily()

    /** Commands of the registry this car does not offer: the ones it cannot do, and any the registry itself drops. */
    val withheld: Set<String> = entries.map { it.id }.toSet() - known.map { it.id }.toSet()
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
    val labelPl: String? = null,
    val deviceType: Int? = null,
    val featureId: Int? = null,
    val featureName: String? = null,
    val invocation: InvocationDto,
    val parameter: ParameterDto,
    val stateFeatureId: Int? = null,
    val stateFeatureName: String? = null,
    val valueOffset: Int = 0,
    val inverted: Boolean = false,
    val stateOffset: Int = 0,
    val stateTolerance: Int = 0,
    val gradual: Boolean = false,
    val requires: RequiresDto? = null,
    val overrides: List<OverrideDto> = emptyList(),
    /**
     * Nothing is sent, on a car that is not a recognised DiLink 5, unless the car answers a read of the state first: its
     * control may exist by name and still not be wired to anything (a window's percent control). A DiLink 5 car is
     * where the control was verified, so it is not asked.
     */
    val requiresReadbackElsewhere: Boolean = false,
    /** Set from [requiresReadbackElsewhere] and the car's platform, never read from the file. */
    @kotlinx.serialization.Transient
    val requiresReadback: Boolean = false,
    /** For an A/C command: how the same control is done on a car with no A/C service (see [AcRoute.DEVICE]). */
    val withoutAcService: DeviceRouteDto? = null,
    /** Set from [DeviceRouteDto] when the entry takes the device route, never read from the file. */
    @kotlinx.serialization.Transient
    val nativeFeatureId: Int? = null,
    @kotlinx.serialization.Transient
    val nativeFeatureName: String? = null,
)

/**
 * An A/C command done through the car's climate device class instead of the A/C service: the call
 * ([invocation], a named method of that class) and the state that reports its result, read back as for any other command.
 * [requires] is a state that must hold first (the temperature is only set on a car showing Celsius). The values the user
 * and the model deal in are those of the command itself and do not change.
 */
@Serializable
internal data class DeviceRouteDto(
    val invocation: InvocationDto,
    val stateFeatureId: Int? = null,
    val stateFeatureName: String? = null,
    val requires: RequiresDto? = null,
    /** The one feature the typed method writes, for the native service to write when the device class refuses the helper. */
    val nativeFeatureId: Int? = null,
    val nativeFeatureName: String? = null,
)

/**
 * The entry as a car with the climate system on [route] has it: itself for the A/C service (or an entry that is not an A/C
 * command), the device route for a car without the service, and null (not offered) where it has neither or the command has no
 * device route.
 */
internal fun CommandEntryDto.forAcRoute(route: AcRoute?): CommandEntryDto? {
    if (invocation.type != "acBinderProperty") return this
    return when (route) {
        null, AcRoute.SERVICE -> this
        AcRoute.DEVICE -> withoutAcService?.let {
            copy(
                invocation = it.invocation,
                stateFeatureId = it.stateFeatureId,
                stateFeatureName = it.stateFeatureName,
                requires = it.requires,
                nativeFeatureId = it.nativeFeatureId,
                nativeFeatureName = it.nativeFeatureName,
                withoutAcService = null,
            )
        }
        AcRoute.NONE -> null
    }
}

@Serializable
internal data class OverrideDto(val whenValue: Int, val featureId: Int, val sendValue: Int, val featureName: String? = null)

@Serializable
internal data class RequiresDto(val featureId: Int, val equals: Int, val message: String, val featureName: String? = null)

@Serializable
internal data class InvocationDto(
    val type: String,
    // namedMethod
    val deviceClass: String? = null,
    val methodName: String? = null,
    val paramTypes: List<String>? = null,
    val argsTemplate: List<Int?>? = null,
    val offMethodName: String? = null,
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
        featureName = featureName,
        displayName = displayName,
        label = label,
        labelAr = labelAr,
        labelRu = labelRu,
        labelPl = labelPl,
        invocation = invocation.toVehicleInvocation(id),
        parameter = parameter.toVehicleParameter(id),
        stateFeatureId = stateFeatureId,
        stateFeatureName = stateFeatureName,
        valueOffset = valueOffset,
        inverted = inverted,
        stateOffset = stateOffset,
        stateTolerance = stateTolerance,
        gradual = gradual,
        requires = requires?.let { StateRequirement(it.featureId, it.equals, it.message, it.featureName) },
        overrides = overrides.map { ValueOverride(it.whenValue, it.featureId, it.sendValue, it.featureName) },
        requiresReadback = requiresReadback,
        nativeFeatureId = nativeFeatureId,
        nativeFeatureName = nativeFeatureName,
    )
}

private fun InvocationDto.toVehicleInvocation(commandId: String): VehicleInvocation = when (type) {
    "genericFeatureSet" -> VehicleInvocation.GenericFeatureSet
    "namedMethod" -> VehicleInvocation.NamedMethod(
        deviceClass = deviceClass ?: error("namedMethod invocation for '$commandId' missing deviceClass"),
        methodName = methodName ?: error("namedMethod invocation for '$commandId' missing methodName"),
        paramTypes = paramTypes ?: error("namedMethod invocation for '$commandId' missing paramTypes"),
        argsTemplate = argsTemplate ?: error("namedMethod invocation for '$commandId' missing argsTemplate"),
        offMethodName = offMethodName,
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

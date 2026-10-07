package com.bydassistantng.vehicle

/**
 * A single known vehicle control, as listed in `vehicle_commands.json`.
 * [deviceType]/[featureId] follow the BYD HAL's "generic feature route"
 * (`AbsBYDAutoDevice.set(deviceType, int[]{featureId}, int[]{value})`) and are used when
 * [invocation] is [VehicleInvocation.GenericFeatureSet]. [featureId] is also reused as the
 * property `id` argument when [invocation] is [VehicleInvocation.AcBinderProperty] — the same BYD
 * signal-numbering scheme shows up in both invocation surfaces. [NamedMethod][VehicleInvocation.NamedMethod]
 * commands don't have a hex feature code, so both are null there.
 */
data class VehicleCommand(
    val id: String,
    val domain: VehicleDomain,
    val deviceType: Int? = null,
    val featureId: Int? = null,
    val displayName: String,
    /** Short names for the status banner (the long [displayName] is written for the model). */
    val label: String? = null,
    val labelAr: String? = null,
    val invocation: VehicleInvocation,
    val parameter: VehicleParameter,
    /** For [VehicleInvocation.GenericFeatureSet]: the *state* id that reports what [featureId] (a
     * control id) did, on the same device. When set, a dispatch only reports success once the car
     * reads back the value that was sent — the HAL accepts a set it doesn't understand without any
     * error, so "accepted" alone proves nothing. Null = no known read-back; reported as sent only. */
    val stateFeatureId: Int? = null,
    /** Added to the value before it is sent to (and compared with) the car, for controls whose
     * signal isn't what the car's own screen shows — e.g. the fridge's cooling set-point reads 13..25
     * for the −6..6 °C on its screen. The value users and the model deal in is always the screen one. */
    val valueOffset: Int = 0,
    /** How far the read-back may be from the value sent and still count as arrived (a window stops a
     * percent or two off its target). */
    val stateTolerance: Int = 0,
    /** The state travels to the target over seconds (a window), so verification also accepts "has
     * started moving toward it" rather than waiting for arrival. */
    val gradual: Boolean = false,
    /** A state that must hold before the command is sent, or it is refused without touching the car. */
    val requires: StateRequirement? = null,
    /** For particular values, a different control and value to send instead (massage "off" is a mode
     * write, not an intensity of 0). The read-back still expects the value's normal state. */
    val overrides: List<ValueOverride> = emptyList(),
)

/** When the command's value is [whenValue], send [sendValue] to [featureId] rather than the usual. */
data class ValueOverride(val whenValue: Int, val featureId: Int, val sendValue: Int)

/** The name to show for this command in the given language, falling back to [VehicleCommand.displayName]. */
fun VehicleCommand.labelFor(arabic: Boolean): String = (if (arabic) labelAr else label) ?: displayName

/** [featureId] (a state id on the command's device) must currently read [equals]. [message] is what
 * the user hears when it doesn't. */
data class StateRequirement(val featureId: Int, val equals: Int, val message: String)

sealed interface VehicleParameter {
    /** e.g. open/close/stop/half/breath -> 1/2/3/4/5. */
    data class FixedEnum(val options: Map<String, Int>) : VehicleParameter

    /** e.g. AC temperature 17..33 °C. */
    data class Range(val min: Int, val max: Int, val unit: String? = null) : VehicleParameter
}

sealed interface VehicleInvocation {
    /** `AbsBYDAutoDevice.set(deviceType, int[]{featureId}, int[]{value})` — documented as `protected`. */
    data object GenericFeatureSet : VehicleInvocation

    /**
     * A named typed setter on a device singleton, e.g. `setSeatHeatingState1(seat, level)`.
     * [paramTypes] is the reflection signature (e.g. `["int", "int"]`) used to look up the method.
     * [argsTemplate] is the actual call arguments with exactly one `null` marking the position the
     * dispatch-time value fills.
     */
    data class NamedMethod(
        val deviceClass: String,
        val methodName: String,
        val paramTypes: List<String>,
        val argsTemplate: List<Int?>,
    ) : VehicleInvocation {
        init {
            require(argsTemplate.count { it == null } == 1) {
                "argsTemplate must have exactly one null (the dispatch-time value slot): $argsTemplate"
            }
            require(paramTypes.size == argsTemplate.size) {
                "paramTypes and argsTemplate must be the same length"
            }
        }
    }

    /**
     * A/C climate controls, through BYD's own `com.byd.ac` service (the same one its A/C screen
     * uses): resolve the named sub-binder of the `"byd_airconditioning"` system service, then
     * `setBydAutoAcValue(PropertyValue(propertyId, area, value))`.
     *
     * [propertyId] is the A/C service's own id space (`com.byd.ac.PropertyIds.AirConditioner`, e.g.
     * 101 = power, 103 = fan) — NOT the HAL feature id in [VehicleCommand.featureId], which is a
     * different numbering and is silently ignored here. [area] must be one the service answers for:
     * 256 is the front/driver zone; with 0 or -1 every call is accepted and nothing happens.
     * See [ReflectionVehicleController] for the transaction codes and Parcel shape.
     */
    data class AcBinderProperty(
        val subServiceKey: String,
        val interfaceDescriptor: String,
        val propertyId: Int,
        val area: Int,
    ) : VehicleInvocation
}

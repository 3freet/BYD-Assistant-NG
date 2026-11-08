package com.bydassistantng.vehicle

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.reflect.InvocationTargetException

/**
 * **Experimental — only reached when the user explicitly arms vehicle control.** Built from observing
 * how BYD DiLink head units call their own vehicle services.
 *
 * This does the raw HAL call and must run as the ADB shell identity: in the app's own process the
 * HAL refuses every call ("[getInt] permission deny!") because the `BYDAUTO_*` permissions are
 * signature-level. It is therefore only ever invoked from [VehicleShellHelper]; the app itself uses
 * [ShellHelperVehicleController].
 *
 * Two independent, evidenced invocation shapes are attempted:
 *  - [VehicleInvocation.GenericFeatureSet]/[VehicleInvocation.NamedMethod] — reflection onto the
 *    `android.hardware.bydauto.<device>.BYDAuto<Device>Device` HAL classes.
 *  - [VehicleInvocation.AcBinderProperty] — a raw Binder transaction against a named sub-service
 *    of the `"byd_airconditioning"` system service, the same one the head unit's own A/C screen
 *    uses. A car without that service (see [AcRoute]) has its A/C commands as [VehicleInvocation.NamedMethod]
 *    calls on the climate device class instead, so none of them reaches this mechanism there.
 *
 * Commands that declare a state to read back are confirmed by it; for the rest,
 * [VehicleDispatchResult.Success] only means "the call did not throw," never "the car actually moved."
 */
class ReflectionVehicleController(private val context: Context) : VehicleController {
    private val instanceCache = HashMap<String, Any?>()

    override suspend fun dispatch(command: VehicleCommand, value: Int): VehicleDispatchResult {
        VehicleSafety.assertDispatchAllowed(command)

        val result = withContext(Dispatchers.IO) {
            when (val invocation = command.invocation) {
                is VehicleInvocation.NamedMethod -> tryNamedMethod(invocation, command, value)
                VehicleInvocation.GenericFeatureSet -> tryGenericSet(command, value)
                is VehicleInvocation.AcBinderProperty -> tryAcBinderProperty(invocation, command, value)
                is VehicleInvocation.BinderCommand -> tryBinderCommand(invocation, command, value)
                is VehicleInvocation.AudioStreamVolume -> StreamVolume.set(context, invocation.stream, value)
            }
        }

        return result
    }

    override suspend fun query(query: VehicleQuery): VehicleDispatchResult = withContext(Dispatchers.IO) {
        when (query) {
            VehicleQuery.OUTSIDE_TEMPERATURE -> readOutsideTemperature()
            VehicleQuery.INSIDE_TEMPERATURE -> readInsideTemperature()
            VehicleQuery.TYRES -> TyreReader(context).read().fold(
                onSuccess = { VehicleDispatchResult.Success(TyreStatusTool.encode(it)) },
                onFailure = { queryFailure(it) },
            )
            VehicleQuery.AIR_QUALITY -> AirQualityTool.read(context).fold(
                onSuccess = { VehicleDispatchResult.Success(AirQualityTool.encode(it)) },
                onFailure = { queryFailure(it) },
            )
            VehicleQuery.HEADREST_SOUND -> readHeadrestSound()
            VehicleQuery.DRIVER_PRESENCE -> readDriverPresence()
        }
    }

    /** The outside air temperature from the cluster, and the unit the cluster shows it in. */
    private suspend fun readOutsideTemperature(): VehicleDispatchResult = try {
        linked(OutsideTemperatureTool.DEVICE_CLASS) { link ->
            val reading = when (link) {
                // The cluster's own getters, as before: they give a below-zero temperature, which a plain state read does not.
                is KitLink -> OutsideTemperatureTool.readFrom(link.instance)
                is NativeLink -> OutsideTemperatureTool.reading(
                    link.readRaw(OutsideTemperatureTool.DEVICE_TYPE, OutsideTemperatureTool.VALUE_NAME, OutsideTemperatureTool.VALUE_ID),
                    link.readRaw(OutsideTemperatureTool.DEVICE_TYPE, OutsideTemperatureTool.UNIT_NAME, OutsideTemperatureTool.UNIT_ID),
                )
            }
            VehicleDispatchResult.Success(OutsideTemperatureTool.encode(reading))
        }
    } catch (t: Throwable) {
        queryFailure(t)
    }

    /** The cabin air temperature from the climate system's inside sensor, and the unit that system shows temperatures in. */
    private suspend fun readInsideTemperature(): VehicleDispatchResult = try {
        linked(AC_DEVICE_CLASS) { link ->
            val celsius = InsideTemperatureTool.usable(
                link.readNamed(InsideTemperatureTool.DEVICE_TYPE, InsideTemperatureTool.STATE_NAME, InsideTemperatureTool.STATE_ID),
            )
            // The unit by the device class's own getter, or as the state that getter reads where the class is not used.
            val unit = (link as? KitLink)?.let { it.instance.javaClass.getMethod("getTemperatureUnit").invoke(it.instance) as? Int }
                ?: link.readNamed(InsideTemperatureTool.DEVICE_TYPE, AC_UNIT_NAME, AC_UNIT_ID)
            VehicleDispatchResult.Success(InsideTemperatureTool.encode(RawInsideTemperature(celsius, unit)))
        }
    } catch (t: Throwable) {
        queryFailure(t)
    }

    /** Whether the driver's seat is occupied and the driver's door is shut, as the car reports them (see [DriverPresence]). */
    private suspend fun readDriverPresence(): VehicleDispatchResult = try {
        val bodywork = GENERIC_ROUTE_CLASS_BY_DOMAIN.getValue(VehicleDomain.BODYWORK)
        val seat = linked(DriverPresence.SEAT_CLASS) {
            it.readNamed(DriverPresence.SEAT_DEVICE_TYPE, DriverPresence.SEAT_STATE_NAME, DriverPresence.SEAT_STATE_ID)
        }
        val door = linked(bodywork) {
            it.readNamed(DriverPresence.DOOR_DEVICE_TYPE, DriverPresence.DOOR_STATE_NAME, DriverPresence.DOOR_STATE_ID)
        }
        VehicleDispatchResult.Success(DriverPresence.encode(DriverPresence.State(seat, door)))
    } catch (t: Throwable) {
        queryFailure(t)
    }

    /** The headrest sound mode and the number of headrest speakers, as the car reports them (see [HeadrestSound]). */
    private suspend fun readHeadrestSound(): VehicleDispatchResult = try {
        linked(GENERIC_ROUTE_CLASS_BY_DOMAIN.getValue(VehicleDomain.AUDIO)) { link ->
            val mode = link.readNamed(HeadrestSound.DEVICE_TYPE, HeadrestSound.MODE_STATE_NAME, HeadrestSound.MODE_STATE_ID)
            val speakers = link.readNamed(HeadrestSound.DEVICE_TYPE, HeadrestSound.SPEAKER_CONFIG_STATE_NAME, HeadrestSound.SPEAKER_CONFIG_STATE_ID)
            VehicleDispatchResult.Success(HeadrestSound.encode(HeadrestSound.State(mode, speakers)))
        }
    } catch (t: Throwable) {
        queryFailure(t)
    }

    private fun queryFailure(t: Throwable): VehicleDispatchResult {
        // A reflective call wraps whatever it threw in an exception with no message of its own.
        val cause = (t as? InvocationTargetException)?.targetException ?: t
        PermissionRefusal.missingPermission(cause)?.let {
            return VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, PermissionRefusal.message(it, wasCommand = false))
        }
        val missing = cause is ClassNotFoundException || cause is NoSuchMethodException
        return VehicleDispatchResult.Failure(
            if (missing) VehicleDispatchError.CLASS_NOT_FOUND else VehicleDispatchError.INVOCATION_FAILED,
            cause.toString(),
        )
    }

    /**
     * A call that threw [cause] (the car's own refusal of the process, or any other failure). A refusal by the car's permission
     * check is worded for the person in the car, so that the assistant can say why it cannot; anything else keeps its message.
     */
    private fun threw(cause: Throwable?, security: Boolean = false): VehicleDispatchResult {
        PermissionRefusal.missingPermission(cause)?.let {
            return VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, PermissionRefusal.message(it, wasCommand = true))
        }
        return VehicleDispatchResult.Failure(if (security) VehicleDispatchError.SECURITY_DENIED else VehicleDispatchError.INVOCATION_FAILED, cause?.message)
    }

    /** Who holds the permissions that guard the car's devices, for the log (see [PermissionRefusal.report]). */
    internal fun permissionReport(appPackage: String): String = PermissionRefusal.report(context, appPackage)

    /**
     * A typed setter. A command that names a [VehicleCommand.stateFeatureId] and a device type is confirmed
     * by reading that state back, as the generic route is: the method's argument is [value] plus
     * [VehicleCommand.valueOffset], and the state is expected to read [value] plus [VehicleCommand.stateOffset].
     * One with a [VehicleCommand.requires] is refused, untouched, unless the car is in that state.
     *
     * A command that also names the one feature the setter writes ([VehicleCommand.nativeFeatureId]) is written to the car's
     * native service instead when the car refuses the helper the device class, as a generic feature set is.
     */
    private suspend fun tryNamedMethod(invocation: VehicleInvocation.NamedMethod, command: VehicleCommand, value: Int): VehicleDispatchResult {
        val className = "android.hardware.bydauto.${invocation.deviceClass}.BYDAuto${invocation.deviceClass.replaceFirstChar { it.uppercase() }}Device"
        val nativeControl = command.nativeFeatureId
        val deviceType = command.deviceType
        return try {
            if (nativeControl != null && deviceType != null) {
                linked(className) { link ->
                    when (link) {
                        is KitLink -> typedCall(link.instance, invocation, command, value)
                        is NativeLink -> runGeneric(command.writtenAsFeature(nativeControl), deviceType, nativeControl, value, link)
                    }
                }
            } else {
                val instance = getOrCreateInstance(className) ?: return VehicleDispatchResult.Failure(
                    VehicleDispatchError.CLASS_NOT_FOUND, "No usable instance for $className"
                )
                typedCall(instance, invocation, command, value)
            }
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Class not found: $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, e.message)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Method not found: ${invocation.methodName} on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.METHOD_NOT_FOUND, e.message)
        } catch (e: SecurityException) {
            Log.w(TAG, "Security denied calling ${invocation.methodName}", e)
            threw(e, security = true)
        } catch (e: InvocationTargetException) {
            Log.w(TAG, "${invocation.methodName} threw", e.targetException)
            threw(e.targetException)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error calling ${invocation.methodName}", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }

    /** The command as a write of the feature its typed setter writes. */
    private fun VehicleCommand.writtenAsFeature(featureId: Int): VehicleCommand =
        copy(invocation = VehicleInvocation.GenericFeatureSet, featureId = featureId, featureName = nativeFeatureName)

    /** The typed setter on the device object, then its state read back. */
    private suspend fun typedCall(
        instance: Any,
        invocation: VehicleInvocation.NamedMethod,
        command: VehicleCommand,
        value: Int,
    ): VehicleDispatchResult {
        command.requires?.let { requirement ->
            val actual = command.deviceType?.let { readState(instance, it, requirement.featureId) }
            if (actual != requirement.equals) {
                Log.i(TAG, "${command.id} refused: state ${requirement.featureId} reads $actual, needs ${requirement.equals}")
                return VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, requirement.message)
            }
        }
        val paramTypes = invocation.paramTypes.map { intClassFor(it) }.toTypedArray()
        val args = invocation.argsTemplate.map { it ?: command.signalFor(value) }.toTypedArray()

        val method = findMethod(instance.javaClass, invocation.methodFor(value), *paramTypes)
        val accepted = toResult(method.invoke(instance, *args))
        val deviceType = command.deviceType
        val stateId = command.stateFeatureId
        return if (accepted is VehicleDispatchResult.Success && deviceType != null && stateId != null) {
            verifyGenericState(KitLink(instance), command, deviceType, stateId, value + command.stateOffset, before = null) { it }
        } else {
            accepted
        }
    }

    /** `AbsBYDAutoDevice.set(deviceType, int[]{featureId}, int[]{value})` — documented `protected`.
     * A command with a [VehicleCommand.stateFeatureId] is confirmed by reading that state back; one
     * with a [VehicleCommand.requires] is refused, untouched, unless the car is in that state. Where the car refuses the
     * helper the device class, the same feature is written to the car's native service instead. */
    private suspend fun tryGenericSet(command: VehicleCommand, value: Int): VehicleDispatchResult {
        val deviceType = command.deviceType
        val featureId = command.featureId
        if (deviceType == null || featureId == null) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "Missing deviceType/featureId for ${command.id}")
        }

        // The generic route's owning device class isn't recorded per-command since it's shared
        // across a whole service group; resolve by domain instead of a hardcoded class guess.
        // CLIMATE deliberately has no entry here: the climate system is reached through the A/C service, or on a car
        // without it through the typed methods of its device class (a NamedMethod), never through a bare feature set.
        val className = GENERIC_ROUTE_CLASS_BY_DOMAIN[command.domain] ?: return VehicleDispatchResult.Failure(
            VehicleDispatchError.CLASS_NOT_FOUND, "No known HAL class for domain ${command.domain}"
        )

        return try {
            linked(className) { link -> runGeneric(command, deviceType, featureId, value, link) }
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Class not found: $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, e.message)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "set() not found on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.METHOD_NOT_FOUND, e.message)
        } catch (e: SecurityException) {
            Log.w(TAG, "Security denied calling set() on $className", e)
            threw(e, security = true)
        } catch (e: InvocationTargetException) {
            Log.w(TAG, "set() threw on $className", e.targetException)
            threw(e.targetException)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error calling set() on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }

    /** One generic feature set over [link]: the precondition, the value to send, the write and the read-back. */
    private suspend fun runGeneric(command: VehicleCommand, deviceType: Int, featureId: Int, value: Int, link: FeatureLink): VehicleDispatchResult {
        command.requires?.let { requirement ->
            val actual = link.read(deviceType, requirement.featureId)
            if (actual != requirement.equals) {
                Log.i(TAG, "${command.id} refused: state ${requirement.featureId} reads $actual, needs ${requirement.equals}")
                return VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, requirement.message)
            }
        }
        val expectedState = command.signalFor(value)
        val override = command.overrides.firstOrNull { it.whenValue == value }
        val controlId = override?.featureId ?: featureId
        val sent = override?.sendValue ?: expectedState
        val stateId = command.stateFeatureId
        val before = if ((command.gradual || command.requiresReadback) && stateId != null) link.read(deviceType, stateId) else null
        if (command.requiresReadback && stateId != null && before == null) {
            Log.i(TAG, "${command.id} refused: state $stateId gives no reading, so the control is not wired on this car")
            return VehicleDispatchResult.Failure(VehicleDispatchError.NOT_AVAILABLE_ON_THIS_CAR, NOT_REPORTED_NOTE)
        }
        val accepted = link.write(deviceType, controlId, sent)
        return if (accepted is VehicleDispatchResult.Success && stateId != null) {
            verifyGenericState(link, command, deviceType, stateId, expectedState, before) { command.valueFor(it) }
        } else {
            accepted
        }
    }

    /** Polls briefly: the car takes a moment to report a new value after accepting it. The user is
     * told values as their screen shows them ([toValue] takes the command's offset, or inversion, back off). For a
     * [VehicleCommand.gradual] state, [before] is the reading at the time of the set, and "has set off
     * toward the target" counts as success too. */
    private suspend fun verifyGenericState(
        link: FeatureLink,
        command: VehicleCommand,
        deviceType: Int,
        stateId: Int,
        expected: Int,
        before: Int?,
        toValue: (Int) -> Int,
    ): VehicleDispatchResult {
        var last: Int? = null
        repeat(STATE_VERIFY_ATTEMPTS) {
            delay(STATE_VERIFY_INTERVAL_MS)
            val now = link.read(deviceType, stateId)
            last = now
            if (now != null) {
                if (kotlin.math.abs(now - expected) <= command.stateTolerance) {
                    return VehicleDispatchResult.Success("confirmed: the car now reports ${toValue(now)}${link.tag}")
                }
                if (command.gradual && before != null && now != before && (now - before) * (expected - before) > 0) {
                    return VehicleDispatchResult.Success("under way: now at ${toValue(now)}, heading for ${toValue(expected)}${link.tag}")
                }
            }
        }
        val actual = last
        // No usable reading is the car's "can't answer", not a value — the set may well have worked.
        return when {
            actual == null -> VehicleDispatchResult.Success("sent, but the car wouldn't report a value to confirm it${link.tag}")
            command.gradual && actual == before ->
                VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "The car accepted it but nothing moved: still at ${toValue(actual)}")
            else -> VehicleDispatchResult.Failure(
                VehicleDispatchError.INVOCATION_FAILED,
                "The car did not apply it: asked for ${toValue(expected)} but it reports ${toValue(actual)}",
            )
        }
    }

    // ── The two ways to the car ─────────────────────────────────────────────────────────────────────

    /**
     * How one feature of the car is read and written: through its device class (the kit), or straight to the car's native
     * service ([NativeAutoservice]). The commands do not know which: they ask the link.
     */
    private sealed interface FeatureLink {
        /** What follows the notes of this route in the log; the model is never told (see [CommandAnswers]). */
        val tag: String

        fun read(deviceType: Int, featureId: Int): Int?

        fun write(deviceType: Int, featureId: Int, value: Int): VehicleDispatchResult

        /** A state the app reads outside the command registry, by its name in the car's own table ([CarNumbers]). */
        fun readNamed(deviceType: Int, name: String, verified: Int): Int? = CarNumbers.of(name, verified)?.let { read(deviceType, it) }
    }

    private inner class KitLink(val instance: Any) : FeatureLink {
        override val tag = ""

        override fun read(deviceType: Int, featureId: Int): Int? = readState(instance, deviceType, featureId)

        override fun write(deviceType: Int, featureId: Int, value: Int): VehicleDispatchResult {
            val method = findMethod(instance.javaClass, "set", Int::class.java, IntArray::class.java, IntArray::class.java)
            return toResult(method.invoke(instance, deviceType, intArrayOf(featureId), intArrayOf(value)))
        }
    }

    private inner class NativeLink(private val service: NativeAutoservice) : FeatureLink {
        override val tag = NativeAnswer.NATIVE_TAG

        override fun read(deviceType: Int, featureId: Int): Int? = NativeAnswer.readValue(service.getInt(deviceType, featureId))

        /** A state that can be below zero (a temperature): whatever the service answered, by its name in the car's table. */
        fun readRaw(deviceType: Int, name: String, verified: Int): Int? =
            CarNumbers.of(name, verified)?.let { NativeAnswer.rawValue(service.getInt(deviceType, it)) }

        override fun write(deviceType: Int, featureId: Int, value: Int): VehicleDispatchResult {
            val result = NativeAnswer.writeResult(service.setInt(deviceType, featureId, value))
            Log.i(TAG, "native set ($deviceType, $featureId) <- $value: $result")
            return result
        }
    }

    private fun nativeOrNull(): NativeAutoservice? = NativeRoute.service()

    /**
     * Runs [work] against the car's device class [className] (the kit) or, where the car's own permission check refuses this
     * process that class, straight against its native service. The refusal means nothing was carried out, so doing the same
     * the other way cannot do it twice. A class the car has refused once (here, in the start-up check or by the drive mode) is
     * not tried again: [NativeRoute] remembers it. [NativeMode] can prefer the service from the start, or forbid it, for a test.
     */
    private suspend fun <T> linked(className: String, work: suspend (FeatureLink) -> T): T {
        val mode = NativeMode.current()
        if (NativeRoute.preferNative(mode, NativeRoute.isRefused(className))) {
            nativeOrNull()?.let { return work(NativeLink(it)) }
        }
        try {
            val instance = getOrCreateInstance(className) ?: throw ClassNotFoundException("No usable instance for $className")
            return work(KitLink(instance))
        } catch (e: Exception) {
            val refused = PermissionRefusal.missingPermission(e)
            val service = if (refused != null && NativeRoute.fallBack(mode)) nativeOrNull() else null
            if (service == null) throw e
            NativeRoute.markRefused(className)
            Log.i(TAG, "$className refuses this process ($refused): going straight to the car's service")
            return work(NativeLink(service))
        }
    }

    /**
     * For the log: whether the car's native service can be spoken to, how many of the states the commands read it answered, how
     * many of those agree with what the device classes say (where the car lets the helper use them), and how long the check took
     * in milliseconds (it runs once, as the helper starts).
     *
     * A device class the car's permission check refuses here is remembered ([NativeRoute]), so that the commands that follow go
     * straight to the service and do not try the class first.
     */
    internal fun nativeReport(): String {
        val service = nativeOrNull() ?: return "-"
        val started = System.nanoTime()
        val link = NativeLink(service)
        val samples = stateSamples()
        var read = 0
        var compared = 0
        var same = 0
        val differ = ArrayList<String>()
        for (sample in samples) {
            val viaService = link.read(sample.deviceType, sample.stateId)
            if (viaService != null) read++
            val viaClass = readThroughClass(sample)
            if (viaService != null && viaClass != null) {
                compared++
                if (viaService == viaClass) same++ else if (differ.size < 3) differ += "${sample.deviceType}:${sample.stateId}=$viaService/$viaClass"
            }
        }
        val milliseconds = (System.nanoTime() - started) / 1_000_000L
        // A state that moved between the two reads (a window) differs too; every one differing would mean the answers are read wrongly.
        return "reads:$read/${samples.size},agree:$same/$compared" +
            (if (differ.isEmpty()) "" else ",differ:" + differ.joinToString("+")) + ",ms:$milliseconds"
    }

    /**
     * For the log: the current numbers of the states [StatePeek] names that the car's [table] has, read straight from the car's native
     * service (read only); null where there is no service to speak to or the car has none of them.
     */
    internal fun peekReport(table: Map<String, Int>?): String? {
        if (table == null || !NativeRoute.fallBack(NativeMode.current())) return null
        val service = nativeOrNull() ?: return null
        return StatePeek.describe(table) { deviceType, id -> NativeAnswer.rawValue(service.getInt(deviceType, id)) }
    }

    /** What the device class says of [sample]; null when it does not answer, or when the car's permission check refuses this process the class. */
    private fun readThroughClass(sample: StateSample): Int? {
        if (NativeRoute.isRefused(sample.className)) return null
        return try {
            getOrCreateInstance(sample.className)?.let { readState(it, sample.deviceType, sample.stateId) }
        } catch (e: Throwable) {
            if (PermissionRefusal.missingPermission(e) != null) NativeRoute.markRefused(sample.className)
            null
        }
    }

    private class StateSample(val className: String, val deviceType: Int, val stateId: Int)

    /** A few of the states the offered commands read back, spread over the device classes: what the check above reads. */
    private fun stateSamples(limitPerClass: Int = 3, limit: Int = 12): List<StateSample> {
        val byClass = LinkedHashMap<String, MutableList<StateSample>>()
        for (command in VehicleCommandRegistry.known) {
            val deviceType = command.deviceType ?: continue
            val stateId = command.stateFeatureId ?: continue
            val className = GENERIC_ROUTE_CLASS_BY_DOMAIN[command.domain]
                ?: AC_DEVICE_CLASS.takeIf { command.domain == VehicleDomain.CLIMATE }
                ?: continue
            val samples = byClass.getOrPut(className) { ArrayList() }
            if (samples.size < limitPerClass && samples.none { it.stateId == stateId }) samples += StateSample(className, deviceType, stateId)
        }
        return byClass.values.flatten().take(limit)
    }

    /** `AbsBYDAutoDevice.get(deviceType, stateId)`; null when the car has no answer (an unsupported
     * state reads -10011, or 65535 for some) or the call fails. The car refusing this process is not "no answer": it is thrown
     * on, for the caller to word (see [threw] and [queryFailure]). */
    private fun readState(instance: Any, deviceType: Int, stateId: Int): Int? = try {
        val get = findMethod(instance.javaClass, "get", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        (get.invoke(instance, deviceType, stateId) as? Int)?.takeIf { it >= 0 && it != NO_READING }
    } catch (e: Exception) {
        if (PermissionRefusal.missingPermission(e) != null) throw e
        Log.w(TAG, "Could not read state $stateId", e)
        null
    }

    /**
     * A/C controls via BYD's `com.byd.ac` service: resolve the `"byd_airconditioning"` system service
     * (hidden `ServiceManager.getService()`, not root-gated), ask it for the named sub-binder
     * (`IBydAcService.getService`, transaction 3), then `IAcAirConditioner.setBydAutoAcValue`
     * (transaction 3) with a `PropertyValue(propertyId, area, value)`.
     *
     * The service accepts a set it doesn't understand without any error, so success is only reported
     * once the value is **read back** (`getBydAutoAcValue`, transaction 2) and matches — this is what
     * turned "says it's done, nothing happened" into an honest failure.
     */
    private suspend fun tryAcBinderProperty(
        invocation: VehicleInvocation.AcBinderProperty,
        command: VehicleCommand,
        value: Int,
    ): VehicleDispatchResult {
        return try {
            val master = getAcServiceBinder() ?: return VehicleDispatchResult.Failure(
                VehicleDispatchError.CLASS_NOT_FOUND, "System service '${AcService.name}' not found"
            )
            val subBinder = getSubBinder(master, invocation.subServiceKey) ?: return VehicleDispatchResult.Failure(
                VehicleDispatchError.SUB_SERVICE_NOT_FOUND, "Sub-service '${invocation.subServiceKey}' not resolved"
            )
            setAcProperty(subBinder, invocation.interfaceDescriptor, invocation.propertyId, invocation.area, value)
            verifyAcProperty(subBinder, invocation, value)
        } catch (e: SecurityException) {
            Log.w(TAG, "Security denied dispatching ${command.id} via AcBinderProperty", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, e.message)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error dispatching ${command.id} via AcBinderProperty", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }

    /** Polls briefly: the A/C takes a moment to report a new value after accepting it. */
    private suspend fun verifyAcProperty(
        subBinder: IBinder,
        invocation: VehicleInvocation.AcBinderProperty,
        expected: Int,
    ): VehicleDispatchResult {
        var last: Int? = null
        repeat(AC_VERIFY_ATTEMPTS) {
            delay(AC_VERIFY_INTERVAL_MS)
            last = getAcProperty(subBinder, invocation.interfaceDescriptor, invocation.propertyId, invocation.area)
            if (last == expected) return VehicleDispatchResult.Success("confirmed: A/C now reports $expected")
        }
        val actual = last
        // A negative reading is the service's "can't answer" (-10011), not a real value — the set
        // may well have worked, it just can't be confirmed.
        return if (actual == null || actual < 0) {
            VehicleDispatchResult.Success("sent, but the A/C wouldn't report a value to confirm it")
        } else {
            VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "The A/C did not apply it: asked for $expected but it reports $actual")
        }
    }

    /**
     * A command on a system service that answers with a status int: one transaction carrying
     * `(commandCode, value)`. The answer *is* the service's verdict (it knows whether the car is on, the card
     * is present and so on), so there is nothing to read back.
     */
    private fun tryBinderCommand(
        invocation: VehicleInvocation.BinderCommand,
        command: VehicleCommand,
        value: Int,
    ): VehicleDispatchResult {
        return try {
            val binder = serviceBinder(invocation.serviceName) ?: return VehicleDispatchResult.Failure(
                VehicleDispatchError.CLASS_NOT_FOUND, "System service '${invocation.serviceName}' not found",
            )
            val code = transactCommand(binder, invocation, value)
            Log.i(TAG, "${command.id} -> ${invocation.serviceName} answered $code")
            if (code in invocation.successCodes) {
                VehicleDispatchResult.Success("the service accepted it (answer $code)")
            } else {
                VehicleDispatchResult.Failure(
                    VehicleDispatchError.INVOCATION_FAILED,
                    invocation.failureMessages[code] ?: "The service refused it (answer $code).",
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security denied dispatching ${command.id} via BinderCommand", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, e.message)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error dispatching ${command.id} via BinderCommand", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }

    private fun serviceBinder(name: String): IBinder? {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        return serviceManagerClass.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
    }

    private fun transactCommand(binder: IBinder, invocation: VehicleInvocation.BinderCommand, value: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(invocation.interfaceDescriptor)
            data.writeInt(invocation.commandCode)
            data.writeInt(value)
            binder.transact(invocation.transactionCode, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** `android.os.ServiceManager` is `@hide` (not root-gated) — reflection is the only way in. */
    private fun getAcServiceBinder(): IBinder? {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        return serviceManagerClass.getMethod("getService", String::class.java)
            .invoke(null, AcService.name) as? IBinder
    }

    private fun getSubBinder(master: IBinder, subServiceKey: String): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AC_MASTER_INTERFACE_DESCRIPTOR)
            data.writeString(subServiceKey)
            master.transact(TRANSACT_GET_SUB, data, reply, 0)
            reply.readException()
            return reply.readStrongBinder()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Confirmed Parcel shape for a "set" call, shared across every `com.byd.ac.*` sub-interface:
     * `writeInterfaceToken, writeInt(count=1), writeInt(id), writeInt(area), writeString(typeName), writeValue(boxed)`. */
    private fun setAcProperty(subBinder: IBinder, interfaceDescriptor: String, id: Int, area: Int, value: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(interfaceDescriptor)
            data.writeInt(1)
            data.writeInt(id)
            data.writeInt(area)
            data.writeString("java.lang.Integer")
            data.writeValue(value)
            subBinder.transact(TRANSACT_SET_PROPERTY, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Looks up [name] on [type] and every superclass, including non-public methods: the generic
     * `set(int, int[], int[])` is `protected` on `AbsBYDAutoDevice`, so `getMethod` and a
     * `getDeclaredMethod` on the concrete device class alone both miss it. */
    private fun findMethod(type: Class<*>, name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodException("${type.name}.$name(${parameterTypes.joinToString { it.name }})")
    }

    /** The HAL's setters return an int status. Negative is an error code (a read of an unsupported
     * feature comes back -10011); 0 is "accepted". Accepted is not the same as the car having moved. */
    private fun toResult(returned: Any?): VehicleDispatchResult {
        val code = returned as? Int ?: return VehicleDispatchResult.Success()
        Log.i(TAG, "HAL returned $code")
        return if (code < 0) VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "HAL returned error code $code")
        else VehicleDispatchResult.Success("HAL returned $code")
    }

    /** `IAcAirConditioner.getBydAutoAcValue(id, area)`, transaction 2. The reply is the Parcelable
     * PropertyValue: a non-null marker, then `id, area, valueClassName, value`. */
    private fun getAcProperty(subBinder: IBinder, interfaceDescriptor: String, id: Int, area: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(interfaceDescriptor)
            data.writeInt(id)
            data.writeInt(area)
            subBinder.transact(TRANSACT_GET_PROPERTY, data, reply, 0)
            reply.readException()
            if (reply.readInt() == 0) return null
            reply.readInt() // id
            reply.readInt() // area
            reply.readString() // value class name
            reply.readValue(Int::class.java.classLoader) as? Int
        } catch (e: Exception) {
            Log.w(TAG, "Could not read back A/C property $id", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Tries a static `getInstance(Context)` first, falling back to a no-arg constructor. */
    private fun getOrCreateInstance(className: String): Any? = instanceCache.getOrPut(className) {
        val clazz = Class.forName(className)
        try {
            clazz.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (_: NoSuchMethodException) {
            try {
                clazz.getMethod("getInstance").invoke(null)
            } catch (_: NoSuchMethodException) {
                clazz.getDeclaredConstructor().newInstance()
            }
        }
    }

    private fun intClassFor(typeName: String): Class<*> = when (typeName) {
        "int" -> Int::class.javaPrimitiveType!!
        else -> Class.forName(typeName)
    }

    companion object {
        private const val TAG = "VehicleController"

        internal const val AC_SERVICE_NAME = AcService.PRIMARY

        // The climate device's unit of temperature: read as the state its getter reads where the class is not used.
        private const val AC_UNIT_NAME = "Ac.AC_TEMPERATURE_UNIT"
        private const val AC_UNIT_ID = 1077936167
        internal const val AC_DEVICE_CLASS = "android.hardware.bydauto.ac.BYDAutoAcDevice"

        // Matches com.byd.ac.IBydAcService.DESCRIPTOR in the car's own /system/framework/com.byd.ac.jar.
        private const val AC_MASTER_INTERFACE_DESCRIPTOR = AcService.MASTER_DESCRIPTOR

        private const val TRANSACT_GET_SUB = 3
        private const val TRANSACT_SET_PROPERTY = 3
        private const val TRANSACT_GET_PROPERTY = 2

        private const val AC_VERIFY_ATTEMPTS = 12
        private const val AC_VERIFY_INTERVAL_MS = 120L

        // The fridge/seat states follow a set within about a second.
        private const val STATE_VERIFY_ATTEMPTS = 15
        private const val STATE_VERIFY_INTERVAL_MS = 150L
        private const val NO_READING = 65535

        // What the user hears when the car cannot say where a control stands, so it is not sent blind (see [VehicleCommand.requiresReadback]).
        private const val NOT_REPORTED_NOTE =
            "This car does not report where that control is, so it cannot be set to a position here. Nothing was sent."

        internal val GENERIC_ROUTE_CLASS_BY_DOMAIN = mapOf(
            VehicleDomain.BODYWORK to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            VehicleDomain.LIGHT to "android.hardware.bydauto.light.BYDAutoLightDevice",
            VehicleDomain.AUDIO to "android.hardware.bydauto.audio.BYDAutoAudioDevice",
            VehicleDomain.SETTING to "android.hardware.bydauto.setting.BYDAutoSettingDevice",
        )
    }
}

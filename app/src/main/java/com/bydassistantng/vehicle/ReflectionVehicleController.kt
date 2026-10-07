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
 *    uses. For CLIMATE commands, the generic route is tried first, falling back to this mechanism.
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
                is VehicleInvocation.NamedMethod -> tryNamedMethod(invocation, value)
                VehicleInvocation.GenericFeatureSet -> tryGenericSet(command, value)
                is VehicleInvocation.AcBinderProperty -> tryAcBinderProperty(invocation, command, value)
            }
        }

        return result
    }

    private fun tryNamedMethod(invocation: VehicleInvocation.NamedMethod, value: Int): VehicleDispatchResult {
        val className = "android.hardware.bydauto.${invocation.deviceClass}.BYDAuto${invocation.deviceClass.replaceFirstChar { it.uppercase() }}Device"
        return try {
            val instance = getOrCreateInstance(className) ?: return VehicleDispatchResult.Failure(
                VehicleDispatchError.CLASS_NOT_FOUND, "No usable instance for $className"
            )
            val paramTypes = invocation.paramTypes.map { intClassFor(it) }.toTypedArray()
            val args = invocation.argsTemplate.map { it ?: value }.toTypedArray()

            val method = findMethod(instance.javaClass, invocation.methodName, *paramTypes)
            toResult(method.invoke(instance, *args))
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Class not found: $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, e.message)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Method not found: ${invocation.methodName} on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.METHOD_NOT_FOUND, e.message)
        } catch (e: SecurityException) {
            Log.w(TAG, "Security denied calling ${invocation.methodName}", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, e.message)
        } catch (e: InvocationTargetException) {
            Log.w(TAG, "${invocation.methodName} threw", e.targetException)
            VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, e.targetException?.message)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error calling ${invocation.methodName}", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }

    /** `AbsBYDAutoDevice.set(deviceType, int[]{featureId}, int[]{value})` — documented `protected`.
     * A command with a [VehicleCommand.stateFeatureId] is confirmed by reading that state back; one
     * with a [VehicleCommand.requires] is refused, untouched, unless the car is in that state. */
    private suspend fun tryGenericSet(command: VehicleCommand, value: Int): VehicleDispatchResult {
        val deviceType = command.deviceType
        val featureId = command.featureId
        if (deviceType == null || featureId == null) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "Missing deviceType/featureId for ${command.id}")
        }

        // The generic route's owning device class isn't recorded per-command since it's shared
        // across a whole service group; resolve by domain instead of a hardcoded class guess.
        // CLIMATE deliberately has no entry here — the guessed BYDAutoAcDevice class has zero
        // evidence of existing, and a real shipped app uses AcBinderProperty for AC instead.
        val className = GENERIC_ROUTE_CLASS_BY_DOMAIN[command.domain] ?: return VehicleDispatchResult.Failure(
            VehicleDispatchError.CLASS_NOT_FOUND, "No known HAL class for domain ${command.domain}"
        )

        return try {
            val instance = getOrCreateInstance(className) ?: return VehicleDispatchResult.Failure(
                VehicleDispatchError.CLASS_NOT_FOUND, "No usable instance for $className"
            )
            command.requires?.let { requirement ->
                val actual = readState(instance, deviceType, requirement.featureId)
                if (actual != requirement.equals) {
                    Log.i(TAG, "${command.id} refused: state ${requirement.featureId} reads $actual, needs ${requirement.equals}")
                    return VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, requirement.message)
                }
            }
            val expectedState = value + command.valueOffset
            val override = command.overrides.firstOrNull { it.whenValue == value }
            val controlId = override?.featureId ?: featureId
            val sent = override?.sendValue ?: expectedState
            val stateId = command.stateFeatureId
            val before = if (command.gradual && stateId != null) readState(instance, deviceType, stateId) else null
            val method = findMethod(instance.javaClass, "set", Int::class.java, IntArray::class.java, IntArray::class.java)
            val accepted = toResult(method.invoke(instance, deviceType, intArrayOf(controlId), intArrayOf(sent)))
            if (accepted is VehicleDispatchResult.Success && stateId != null) {
                verifyGenericState(instance, command, deviceType, stateId, expectedState, before)
            } else {
                accepted
            }
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Class not found: $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, e.message)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "set() not found on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.METHOD_NOT_FOUND, e.message)
        } catch (e: SecurityException) {
            Log.w(TAG, "Security denied calling set() on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, e.message)
        } catch (e: InvocationTargetException) {
            Log.w(TAG, "set() threw on $className", e.targetException)
            VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, e.targetException?.message)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error calling set() on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }

    /** Polls briefly: the car takes a moment to report a new value after accepting it. The user is
     * told values as their screen shows them (the command's offset taken back off). For a
     * [VehicleCommand.gradual] state, [before] is the reading at the time of the set, and "has set off
     * toward the target" counts as success too. */
    private suspend fun verifyGenericState(
        instance: Any,
        command: VehicleCommand,
        deviceType: Int,
        stateId: Int,
        expected: Int,
        before: Int?,
    ): VehicleDispatchResult {
        val offset = command.valueOffset
        var last: Int? = null
        repeat(STATE_VERIFY_ATTEMPTS) {
            delay(STATE_VERIFY_INTERVAL_MS)
            val now = readState(instance, deviceType, stateId)
            last = now
            if (now != null) {
                if (kotlin.math.abs(now - expected) <= command.stateTolerance) {
                    return VehicleDispatchResult.Success("confirmed: the car now reports ${now - offset}")
                }
                if (command.gradual && before != null && now != before && (now - before) * (expected - before) > 0) {
                    return VehicleDispatchResult.Success("under way: now at ${now - offset}, heading for ${expected - offset}")
                }
            }
        }
        val actual = last
        // No usable reading is the car's "can't answer", not a value — the set may well have worked.
        return when {
            actual == null -> VehicleDispatchResult.Success("sent, but the car wouldn't report a value to confirm it")
            command.gradual && actual == before ->
                VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "The car accepted it but nothing moved: still at ${actual - offset}")
            else -> VehicleDispatchResult.Failure(
                VehicleDispatchError.INVOCATION_FAILED,
                "The car did not apply it: asked for ${expected - offset} but it reports ${actual - offset}",
            )
        }
    }

    /** `AbsBYDAutoDevice.get(deviceType, stateId)`; null when the car has no answer (an unsupported
     * state reads -10011, or 65535 for some) or the call fails. */
    private fun readState(instance: Any, deviceType: Int, stateId: Int): Int? = try {
        val get = findMethod(instance.javaClass, "get", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        (get.invoke(instance, deviceType, stateId) as? Int)?.takeIf { it >= 0 && it != NO_READING }
    } catch (e: Exception) {
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
                VehicleDispatchError.CLASS_NOT_FOUND, "System service '$AC_SERVICE_NAME' not found"
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

    /** `android.os.ServiceManager` is `@hide` (not root-gated) — reflection is the only way in. */
    private fun getAcServiceBinder(): IBinder? {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        return serviceManagerClass.getMethod("getService", String::class.java)
            .invoke(null, AC_SERVICE_NAME) as? IBinder
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

        private const val AC_SERVICE_NAME = "byd_airconditioning"

        // Matches com.byd.ac.IBydAcService.DESCRIPTOR in the car's own /system/framework/com.byd.ac.jar.
        private const val AC_MASTER_INTERFACE_DESCRIPTOR = "com.byd.ac.IBydAcService"

        private const val TRANSACT_GET_SUB = 3
        private const val TRANSACT_SET_PROPERTY = 3
        private const val TRANSACT_GET_PROPERTY = 2

        private const val AC_VERIFY_ATTEMPTS = 12
        private const val AC_VERIFY_INTERVAL_MS = 120L

        // The fridge/seat states follow a set within about a second.
        private const val STATE_VERIFY_ATTEMPTS = 15
        private const val STATE_VERIFY_INTERVAL_MS = 150L
        private const val NO_READING = 65535

        private val GENERIC_ROUTE_CLASS_BY_DOMAIN = mapOf(
            VehicleDomain.BODYWORK to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            VehicleDomain.AUDIO to "android.hardware.bydauto.audio.BYDAutoAudioDevice",
            VehicleDomain.SETTING to "android.hardware.bydauto.setting.BYDAutoSettingDevice",
        )
    }
}

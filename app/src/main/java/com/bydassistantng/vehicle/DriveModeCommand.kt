package com.bydassistantng.vehicle

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * `drivemode set <number>`: asks the car for a drive mode ([DriveModeControl]) exactly as the car's own
 * drive-mode screen does, then watches the car's reported mode for a moment and answers with what it did.
 *
 * It runs inside the already running vehicle helper (`VehicleShellHelper`, started once per conversation
 * through the app's loopback ADB connection), which is a process under the ADB shell identity for the same
 * reason as the rest of the helper: the car's HAL refuses the app's own process. It is a command of its own,
 * not an entry of the vehicle command registry, whose denylist it does not touch.
 *
 * Before anything is sent it checks that the number is one the car's screen sends, that the car's framework
 * calls the feature by the id seen on this car, and that the car is below the speed limit (an unknown speed is
 * not below it). Like the screen, it sends nothing when the car is already in that mode.
 *
 * Two ways reach the car, and they make the same read and the same request: the car's Setting device class, and, where
 * the car's own permission check refuses the helper that class (a DiLink 5.0 car does), the car's native service
 * ([NativeAutoservice]). The checks above are made before either, so the route changes nothing about when a request is sent.
 * The speed is read from the Speed device class, which has no such permission check; where it cannot be read, nothing is sent.
 *
 * Which feature is written depends on the car's own table ([DriveModeControl.route]): the Leopard's Setting feature with every mode of
 * the car's screen, or, on a car that does not name it, the classic Energy operation mode with Eco, Normal and Sport only
 * ([OperationModeRoute]). Both are read and asked the same two ways, and the checks above are the same.
 */
internal object DriveModeCommand {
    const val NAME = "drivemode"

    private const val TAG = "DriveModeCommand"

    internal const val SETTING_DEVICE_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    private const val SPEED_DEVICE_CLASS = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    private const val ABS_DEVICE_CLASS = "android.hardware.bydauto.AbsBYDAutoDevice"
    private const val EVENT_VALUE_CLASS = "android.hardware.bydauto.BYDAutoEventValue"

    private const val EXIT = 255

    // The car answered within 0.25 s every time it was seen; this is generous.
    internal const val WATCH_MS = 2_500L
    internal const val POLL_MS = 25L

    fun run(args: List<String>): VehicleDispatchResult = try {
        execute(args)
    } catch (e: InvocationTargetException) {
        VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, e.targetException?.toString())
    } catch (t: Throwable) {
        VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, t.toString())
    }

    private fun execute(args: List<String>): VehicleDispatchResult {
        if (args.size != 2 || args[0] != "set") {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "expected: $NAME set <number>")
        }
        val value = args[1].toIntOrNull()
            ?: return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "'${args[1]}' is not a number")
        // The values this build may ask for are those of the route the car is on; on a car with none, the Leopard's, as ever.
        val route = DriveModeControl.route()
        val permitted = if (route == DriveModeRoute.OPERATION_MODE) {
            DriveModeControl.isPermitted(route, value)
        } else {
            DriveModeControl.isPermitted(DriveModeControl.DEVICE_TYPE, DriveModeControl.PRESELECT_ID, value)
        }
        if (!permitted) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "$value is not a mode this build may ask for")
        }

        val context = systemContext()
        val speed = speedKmh(context)
        if (!DriveModeControl.mayChangeAt(speed)) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, DriveModeControl.speedRefusal(speed))
        }
        if (route == DriveModeRoute.OPERATION_MODE) return viaOperationMode(context, value, speed)

        val preselect = CarFeatureIds.idOf(DriveModeControl.FEATURE_GROUP, DriveModeControl.PRESELECT_NAME)
        val target = CarFeatureIds.idOf(DriveModeControl.FEATURE_GROUP, DriveModeControl.TARGET_NAME)
        if (preselect != DriveModeControl.PRESELECT_ID || target == null) {
            return VehicleDispatchResult.Failure(
                VehicleDispatchError.PRECONDITION_NOT_MET,
                "this car does not name the drive mode feature as expected (${preselect?.let { "0x%08X".format(it) } ?: "none"}); nothing sent",
            )
        }

        return viaClassOrService(
            mode = NativeMode.current(),
            kit = { KitDevice(context, preselect, target) },
            service = NativeRoute::service,
            preselect = preselect,
            target = target,
            log = { Log.i(TAG, it) },
        ) { device -> carryOut(device, value, speed?.toInt() ?: 0) }
    }

    /** The classic route: the Energy device's operation mode, by the names the car's table gives it. */
    private fun viaOperationMode(context: Context, value: Int, speed: Double?): VehicleDispatchResult {
        val control = CarFeatureIds.idOf(OperationModeRoute.GROUP, OperationModeRoute.CONTROL_NAME)
        val state = CarFeatureIds.idOf(OperationModeRoute.GROUP, OperationModeRoute.STATE_NAME)
        if (control == null || state == null) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, "this car does not name the operation mode as expected; nothing sent")
        }
        return viaClassOrService(
            mode = NativeMode.current(),
            deviceClass = OperationModeRoute.DEVICE_CLASS,
            kit = { OperationKitDevice(context) },
            service = NativeRoute::service,
            native = { OperationNativeDevice(it, control, state) },
            log = { Log.i(TAG, it) },
        ) { device -> carryOut(device, value, speed?.toInt() ?: 0) }
    }

    // ── The two ways to the car ─────────────────────────────────────────────────────────────────────

    /** How the drive mode is read and asked for: through the Setting device class, or straight to the car's native service. */
    internal interface Device {
        /** What follows the helper's note in the log. The model is never told which way the car was reached (see [DriveModeControl.outcomeOf]). */
        val tag: String

        /** The drive mode the car reports, or null when it gives none. */
        fun reportedMode(): Int?

        /** Asks the car for [value]: a success when the car took the request, otherwise why it did not. */
        fun ask(value: Int): VehicleDispatchResult
    }

    /** The car's Setting device class, as the car's own drive-mode screen uses it. Making it can throw the car's permission refusal. */
    private class KitDevice(context: Context, private val preselect: Int, private val target: Int) : Device {
        private val device: Any = Class.forName(SETTING_DEVICE_CLASS).getMethod("getInstance", Context::class.java).invoke(null, context)!!
        private val eventClass = Class.forName(EVENT_VALUE_CLASS)
        private val abs = Class.forName(ABS_DEVICE_CLASS)
        private val get: Method = abs.getMethod("get", IntArray::class.java, Class::class.java)
        private val set: Method = abs.getMethod("set", IntArray::class.java, eventClass)
        private val intField = eventClass.getField("intValue")

        override val tag = ""

        override fun reportedMode(): Int? = get.invoke(device, intArrayOf(target), Int::class.javaPrimitiveType)
            ?.let { intField.getInt(it) }
            ?.takeIf { it >= 0 && it != DriveModeControl.NO_READING }

        override fun ask(value: Int): VehicleDispatchResult {
            val event = eventClass.getConstructor().newInstance()
            intField.setInt(event, value)
            val code = set.invoke(device, intArrayOf(preselect), event) as? Int ?: 0
            return if (code != 0) {
                VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "the car returned error code $code")
            } else {
                VehicleDispatchResult.Success()
            }
        }
    }

    /**
     * The car's Energy device class, through its own `getOperationMode` and `setOperationMode` (which turn the API's numbers into the
     * drive-mode screen's, see [OperationModeRoute]). Making it can throw the car's permission refusal.
     */
    private class OperationKitDevice(context: Context) : Device {
        private val device: Any = Class.forName(OperationModeRoute.DEVICE_CLASS).getMethod("getInstance", Context::class.java).invoke(null, context)!!
        private val read: Method = device.javaClass.getMethod("getOperationMode")
        private val write: Method = device.javaClass.getMethod("setOperationMode", Int::class.javaPrimitiveType)

        override val tag = ""

        override fun reportedMode(): Int? = (read.invoke(device) as? Int)
            ?.takeIf { it >= 0 && it != DriveModeControl.NO_READING }
            ?.let(OperationModeRoute::driveModeOf)

        override fun ask(value: Int): VehicleDispatchResult {
            val operation = OperationModeRoute.operationOf(value)
                ?: return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "$value is not a mode this build may ask for")
            val code = write.invoke(device, operation) as? Int ?: 0
            return if (code != 0) {
                VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "the car returned error code $code")
            } else {
                VehicleDispatchResult.Success()
            }
        }
    }

    /** The same read and request straight to the car's native service, which writes what the class would write to `ENERGY_OPERATION_MODE_SET` ([OperationModeRoute.rawOf]). */
    internal class OperationNativeDevice(private val calls: NativeCalls, private val control: Int, private val state: Int) : Device {
        override val tag = NativeAnswer.NATIVE_TAG

        override fun reportedMode(): Int? =
            NativeAnswer.readValue(calls.getInt(OperationModeRoute.DEVICE_TYPE, state))?.let(OperationModeRoute::driveModeOf)

        override fun ask(value: Int): VehicleDispatchResult {
            // The only line that writes to the car's service for this route: the one feature and the listed values, however it was reached.
            check(OperationModeRoute.isPermitted(OperationModeRoute.DEVICE_TYPE, value)) { "$value is not a mode this build may ask for" }
            val raw = OperationModeRoute.rawOf(value) ?: return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "$value is not a mode this build may ask for")
            return NativeAnswer.writeResult(calls.setInt(OperationModeRoute.DEVICE_TYPE, control, raw))
        }
    }

    /**
     * The car's native service: the same read (`getInt` of the state) and the same request (`setInt` of the one feature) that the
     * device class ends in, without the class's permission check in front of them (see [NativeAutoservice]).
     */
    internal class NativeDevice(private val calls: NativeCalls, private val preselect: Int, private val target: Int) : Device {
        override val tag = NativeAnswer.NATIVE_TAG

        override fun reportedMode(): Int? = NativeAnswer.readValue(calls.getInt(DriveModeControl.DEVICE_TYPE, target))

        override fun ask(value: Int): VehicleDispatchResult {
            // The only line that writes to the car's service for the drive mode: the one feature and the listed values, however it was reached.
            check(DriveModeControl.isPermitted(DriveModeControl.DEVICE_TYPE, preselect, value)) { "$value is not a mode this build may ask for" }
            return NativeAnswer.writeResult(calls.setInt(DriveModeControl.DEVICE_TYPE, preselect, value))
        }
    }

    /**
     * Runs [work] against the car's Setting device class ([kit]) or, where the car's own permission check refuses this process
     * that class, against its native service ([service]): the refusal means nothing was sent, so doing it the other way cannot
     * do it twice. A refusal that cannot be got round (the native route forbidden by [mode] for a test, or the car has no
     * service to speak to) is answered in words for the person and not as the car's exception.
     */
    internal fun viaClassOrService(
        mode: NativeMode,
        kit: () -> Device,
        service: () -> NativeCalls?,
        preselect: Int,
        target: Int,
        log: (String) -> Unit,
        work: (Device) -> VehicleDispatchResult,
    ): VehicleDispatchResult =
        viaClassOrService(mode, SETTING_DEVICE_CLASS, kit, service, { NativeDevice(it, preselect, target) }, log, work)

    /** The same for any device class: [deviceClass] is the class whose refusal is remembered, [native] makes the device that speaks to the service. */
    internal fun viaClassOrService(
        mode: NativeMode,
        deviceClass: String,
        kit: () -> Device,
        service: () -> NativeCalls?,
        native: (NativeCalls) -> Device,
        log: (String) -> Unit,
        work: (Device) -> VehicleDispatchResult,
    ): VehicleDispatchResult {
        if (NativeRoute.preferNative(mode, NativeRoute.isRefused(deviceClass))) {
            service()?.let { return work(native(it)) }
        }
        val failure: Throwable = try {
            return work(kit())
        } catch (e: Exception) {
            e
        } catch (e: LinkageError) {
            e
        }
        // The class refused this process, or the car has no such class (or not the method asked for): nothing was sent either way, so
        // doing it through the car's service cannot do it twice. Any other failure is not this function's to explain.
        val refused = PermissionRefusal.missingPermission(failure)
        val unusable = if (refused == null) classUnusable(failure) else null
        if (refused == null && unusable == null) throw failure
        val calls = (if (NativeRoute.fallBack(mode)) service() else null)
            ?: return if (refused != null) {
                VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, DriveModeControl.REFUSED_REASON)
            } else {
                VehicleDispatchResult.Failure(VehicleDispatchError.NOT_AVAILABLE_ON_THIS_CAR, DriveModeControl.MISSING_REASON)
            }
        NativeRoute.markRefused(deviceClass)
        log("$deviceClass ${if (refused != null) "refuses this process ($refused)" else "cannot be used on this car ($unusable)"}: the drive mode goes straight to the car's service")
        return work(native(calls))
    }

    /**
     * Why a device class cannot be used on this car, when that is the failure: the class is not there (`ClassNotFoundException`, or one that
     * does not link) or lacks the method asked for (`NoSuchMethodException`), looked for through the exceptions that wrap it; null for any
     * other failure. Cars differ in which classes their framework has, and a car that has a feature may lack the class an older car reaches
     * it through.
     */
    internal fun classUnusable(failure: Throwable?): String? {
        var current = failure
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is ClassNotFoundException || current is NoSuchMethodException || current is LinkageError) return current.javaClass.simpleName
            current = (current as? InvocationTargetException)?.targetException ?: current.cause
        }
        return null
    }

    // ── The request ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The request, once the checks have passed: reads the mode the car is in, sends nothing when it is already in the one asked
     * for, asks, and watches the car's reported mode for [WATCH_MS] (it answers within a quarter of a second) before saying what
     * it did. [clock] and [pause] are the time, so that the watching can be checked without waiting.
     */
    internal fun carryOut(
        device: Device,
        value: Int,
        speed: Int,
        clock: () -> Long = { System.nanoTime() },
        pause: (Long) -> Unit = { Thread.sleep(it) },
    ): VehicleDispatchResult {
        val before = device.reportedMode()
            ?: return VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, "the car did not report its drive mode")
        // Like the car's own screen: asking for the mode it is already in sends nothing.
        // Leaving an off-road mode while on the road has nothing to leave.
        if (before == value || (value == EXIT && before in DriveModeControl.ROAD_MODES)) {
            return VehicleDispatchResult.Success(
                DriveModeControl.note(DriveModeControl.Reading(before, before, value, 0, speed)) + device.tag,
            )
        }

        val started = clock()
        val asked = device.ask(value)
        if (asked is VehicleDispatchResult.Failure) return asked

        var now = before
        while (true) {
            val elapsed = (clock() - started) / 1_000_000L
            now = device.reportedMode() ?: now
            val reached = if (value == EXIT) now != before else now == value
            if (reached || elapsed >= WATCH_MS) {
                return VehicleDispatchResult.Success(
                    DriveModeControl.note(DriveModeControl.Reading(before, now, value, elapsed, speed)) + device.tag,
                )
            }
            pause(POLL_MS)
        }
    }

    private fun speedKmh(context: Context): Double? = try {
        val clazz = Class.forName(SPEED_DEVICE_CLASS)
        val device = clazz.getMethod("getInstance", Context::class.java).invoke(null, context)
        clazz.getMethod("getCurrentSpeed").invoke(device) as? Double
    } catch (_: Exception) {
        null
    }

    /** The helper this runs in has already started the system context (its HAL classes need one): reuse it. */
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }
}

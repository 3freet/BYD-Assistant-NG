package com.bydassistantng.vehicle

import android.content.Context
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
 * calls the feature by the id seen on this car, and that the car is below the speed limit. Like the screen, it
 * sends nothing when the car is already in that mode.
 */
internal object DriveModeCommand {
    const val NAME = "drivemode"

    private const val SETTING_DEVICE_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    private const val SPEED_DEVICE_CLASS = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    private const val ABS_DEVICE_CLASS = "android.hardware.bydauto.AbsBYDAutoDevice"
    private const val EVENT_VALUE_CLASS = "android.hardware.bydauto.BYDAutoEventValue"

    private const val EXIT = 255

    // The car answered within 0.25 s every time it was seen; this is generous.
    private const val WATCH_MS = 2_500L
    private const val POLL_MS = 25L

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
        if (!DriveModeControl.isPermitted(DriveModeControl.DEVICE_TYPE, DriveModeControl.PRESELECT_ID, value)) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "$value is not a mode this build may ask for")
        }

        val context = systemContext()
        val speed = speedKmh(context)
        if (!DriveModeControl.mayChangeAt(speed)) {
            return VehicleDispatchResult.Failure(
                VehicleDispatchError.PRECONDITION_NOT_MET,
                "the car is going too fast for a drive mode change " +
                    "(${speed?.let { "%.0f".format(it) } ?: "unknown"} km/h; the limit is ${DriveModeControl.MAX_SPEED_KMH.toInt()})",
            )
        }

        val preselect = CarFeatureIds.idOf(DriveModeControl.FEATURE_GROUP, DriveModeControl.PRESELECT_NAME)
        val target = CarFeatureIds.idOf(DriveModeControl.FEATURE_GROUP, DriveModeControl.TARGET_NAME)
        if (preselect != DriveModeControl.PRESELECT_ID || target == null) {
            return VehicleDispatchResult.Failure(
                VehicleDispatchError.PRECONDITION_NOT_MET,
                "this car does not name the drive mode feature as expected (${preselect?.let { "0x%08X".format(it) } ?: "none"}); nothing sent",
            )
        }

        val device = Class.forName(SETTING_DEVICE_CLASS).getMethod("getInstance", Context::class.java).invoke(null, context)!!
        val abs = Class.forName(ABS_DEVICE_CLASS)
        val eventClass = Class.forName(EVENT_VALUE_CLASS)
        val get: Method = abs.getMethod("get", IntArray::class.java, Class::class.java)
        val set: Method = abs.getMethod("set", IntArray::class.java, eventClass)
        val intField = eventClass.getField("intValue")
        val read: () -> Int? = {
            get.invoke(device, intArrayOf(target), Int::class.javaPrimitiveType)
                ?.let { intField.getInt(it) }
                ?.takeIf { it >= 0 && it != DriveModeControl.NO_READING }
        }

        val before = read()
            ?: return VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, "the car did not report its drive mode")
        val speedInt = speed?.toInt() ?: 0
        // Like the car's own screen: asking for the mode it is already in sends nothing.
        // Leaving an off-road mode while on the road has nothing to leave.
        if (before == value || (value == EXIT && before in DriveModeControl.ROAD_MODES)) {
            return VehicleDispatchResult.Success(
                DriveModeControl.note(DriveModeControl.Reading(before, before, value, 0, speedInt)),
            )
        }

        val event = eventClass.getConstructor().newInstance()
        intField.setInt(event, value)
        val started = System.nanoTime()
        val code = set.invoke(device, intArrayOf(preselect), event) as? Int ?: 0
        if (code != 0) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "the car returned error code $code")
        }

        var now = before
        while (true) {
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            now = read() ?: now
            val reached = if (value == EXIT) now != before else now == value
            if (reached || elapsed >= WATCH_MS) {
                return VehicleDispatchResult.Success(
                    DriveModeControl.note(DriveModeControl.Reading(before, now, value, elapsed, speedInt)),
                )
            }
            Thread.sleep(POLL_MS)
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

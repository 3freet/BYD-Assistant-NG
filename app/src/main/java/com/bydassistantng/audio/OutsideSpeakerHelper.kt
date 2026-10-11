package com.bydassistantng.audio

import android.content.Context
import android.os.Looper
import androidx.annotation.Keep
import com.bydassistantng.vehicle.HelperProtocol
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import java.lang.reflect.InvocationTargetException

/**
 * The shell-identity process that holds the exterior speaker switch ([ExteriorSpeakerSwitch]), started
 * by [OutsideSpeakerSession] through the app's loopback ADB connection:
 *
 *     CLASSPATH=<this app's APK> app_process /system/bin com.bydassistantng.audio.OutsideSpeakerHelper --serve
 *
 * It is a separate process for the same reason as [com.bydassistantng.vehicle.VehicleShellHelper]: the
 * car's HAL refuses the app's own process. It speaks one line per command on stdin:
 *
 *  - `on`     switch the exterior speaker on (and report how the cabin mute state moved)
 *  - `hold`   heartbeat; the switch is switched off by this process if none arrives for a few seconds
 *  - `off`    switch it off
 *  - `state`  read the cabin mute state, write nothing
 *
 * Whatever happens to the app, the switch cannot stay on: end of input (the app died, or the ADB link
 * dropped) switches it off, a missing heartbeat switches it off, and so does a hard time limit.
 */
@Keep
object OutsideSpeakerHelper {
    private const val AUDIO_DEVICE_CLASS = "android.hardware.bydauto.audio.BYDAutoAudioDevice"
    private const val NOTE_PREFIX = "BYDNOTE "
    private const val WATCH_INTERVAL_MS = 250L

    private val lock = Any()
    private var on = false
    private var onSince = 0L
    private var lastHeartbeat = 0L

    /** Set when this process switched the speaker off on its own, and reported with the next answer. */
    private var forcedOff: String? = null

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() != "--serve") {
            emit(HelperProtocol.encode(VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "expected --serve")))
        } else {
            serve()
        }
        // The system context starts non-daemon threads that would otherwise keep this process alive.
        System.exit(0)
    }

    private fun serve() {
        // Verified on one car only: another model may number this feature differently, so nothing is written
        // unless the car's own framework gives the feature's name the id this was verified with.
        if (!ExteriorSpeakerSwitch.isOnThisCar()) {
            emit(
                HelperProtocol.encode(
                    VehicleDispatchResult.Failure(
                        VehicleDispatchError.PRECONDITION_NOT_MET,
                        "this car does not name the exterior speaker feature as expected; nothing sent",
                    ),
                ),
            )
            return
        }
        val device = try {
            Looper.prepareMainLooper()
            open()
        } catch (t: Throwable) {
            emit(HelperProtocol.encode(VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, "helper failed: $t")))
            return
        }
        val set = findMethod(device.javaClass, "set", Int::class.javaPrimitiveType!!, IntArray::class.java, IntArray::class.java)
        val get = findMethod(device.javaClass, "get", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)

        val write: (Int) -> Int = { value ->
            check(ExteriorSpeakerSwitch.isPermitted(ExteriorSpeakerSwitch.DEVICE_TYPE, ExteriorSpeakerSwitch.FEATURE_ID, value))
            set.invoke(
                device,
                ExteriorSpeakerSwitch.DEVICE_TYPE,
                intArrayOf(ExteriorSpeakerSwitch.FEATURE_ID),
                intArrayOf(value),
            ) as? Int ?: 0
        }
        val read: () -> Int? = {
            (get.invoke(device, ExteriorSpeakerSwitch.DEVICE_TYPE, ExteriorSpeakerSwitch.MUTE_STATE_ID) as? Int)
                ?.takeIf { it >= 0 && it != ExteriorSpeakerSwitch.NO_READING }
        }

        val watchdog = Thread {
            while (true) {
                Thread.sleep(WATCH_INTERVAL_MS)
                synchronized(lock) {
                    val reason = ExteriorSpeakerGuard.mustSwitchOff(on, now(), onSince, lastHeartbeat)
                    if (reason != null) {
                        val code = runCatching { write(ExteriorSpeakerSwitch.OFF) }.getOrNull()
                        on = false
                        forcedOff = "switched off by the helper ($reason), HAL returned $code"
                        emit("$NOTE_PREFIX$forcedOff")
                    }
                }
            }
        }.apply { isDaemon = true }
        watchdog.start()

        emit(HelperProtocol.READY_LINE)
        val input = System.`in`.bufferedReader()
        try {
            while (true) {
                val line = input.readLine() ?: break
                val command = line.trim()
                if (command.isEmpty()) continue
                val result = try {
                    synchronized(lock) { handle(command, write, read) }
                } catch (e: InvocationTargetException) {
                    VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, e.targetException?.toString())
                } catch (t: Throwable) {
                    VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, t.toString())
                }
                emit(HelperProtocol.encode(result))
            }
        } finally {
            // The app is gone or its ADB stream closed: never leave the car sending sound outside.
            synchronized(lock) {
                if (on) runCatching { write(ExteriorSpeakerSwitch.OFF) }
                on = false
            }
        }
    }

    private fun handle(command: String, write: (Int) -> Int, read: () -> Int?): VehicleDispatchResult = when (command) {
        "on" -> {
            val before = read()
            val code = write(ExteriorSpeakerSwitch.ON)
            if (code < 0) {
                VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "HAL returned error code $code")
            } else {
                on = true
                forcedOff = null
                onSince = now()
                lastHeartbeat = onSince
                VehicleDispatchResult.Success("accepted ($code); cabin mute state ${before ?: "?"} -> ${read() ?: "?"}")
            }
        }
        "hold" -> {
            lastHeartbeat = now()
            // A heartbeat that finds the switch already off must not look like success.
            forcedOff?.let { VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, it) }
                ?: VehicleDispatchResult.Success(
                    (if (on) "holding, on for ${now() - onSince} ms" else "not on") + "; cabin mute state ${read() ?: "?"}",
                )
        }
        "off" -> {
            val before = read()
            val code = write(ExteriorSpeakerSwitch.OFF)
            on = false
            if (code < 0) {
                VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "HAL returned error code $code")
            } else {
                VehicleDispatchResult.Success("accepted ($code); cabin mute state ${before ?: "?"} -> ${read() ?: "?"}")
            }
        }
        "state" -> VehicleDispatchResult.Success(
            "cabin mute state ${read() ?: "?"}, switch ${if (on) "on" else "off"}" + (forcedOff?.let { "; earlier: $it" } ?: ""),
        )
        else -> VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "unknown command '$command'")
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    private fun emit(line: String) {
        println(line)
        System.out.flush()
    }

    /** Tries a static `getInstance(Context)` first, as the other HAL helpers do. */
    private fun open(): Any {
        val context = systemContext()
        val clazz = Class.forName(AUDIO_DEVICE_CLASS)
        return try {
            clazz.getMethod("getInstance", Context::class.java).invoke(null, context)!!
        } catch (_: NoSuchMethodException) {
            clazz.getMethod("getInstance").invoke(null)!!
        }
    }

    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }

    /** `set` is `protected` on `AbsBYDAutoDevice`, so it has to be looked up through the superclasses. */
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
}

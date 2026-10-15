package com.bydassistantng.passenger

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult

/**
 * `fse <action> ...`: controls the passenger screen from the vehicle helper, which runs under the ADB shell
 * identity and so may start apps for any user on any display, stop them, and switch a display's backlight.
 * Like the drive mode command it is a command of its own, not an entry of the vehicle command registry.
 *
 * The screen is the second display of the head unit (named `fse`, 1920×720, shown to the front passenger). It
 * runs a second Android user (999) with its own apps; apps of the main user (0) can be put on it too.
 *
 *     fse screen off|on            the backlight (the car's own power button on that screen does the same)
 *     fse open <user> <package> [<https url>]
 *     fse close [<package>]        stops the apps that are open on it (never the system's own)
 *     fse key home|back
 *     fse status
 *
 * Every answer is one line of `key=value` words; [VehicleDispatchResult.Failure] carries a sentence.
 */
internal object PassengerScreenCommand {
    const val NAME = "fse"

    private const val DISPLAY_NAME = "fse"
    private const val FALLBACK_DISPLAY_ID = 2
    private const val PASSENGER_USER = 999
    private const val SAFE_LAUNCHER_PACKAGE = "com.android.launcher3.fse"
    private const val OWN_PACKAGE_PREFIX = "com.bydassistantng"

    private const val COMMAND_TIMEOUT_MS = 5_000L
    private const val FIRST_WAIT_MS = 2_500L
    private const val SHOW_WAIT_MS = 3_500L
    private const val POLL_MS = 250L

    fun run(args: List<String>): VehicleDispatchResult = try {
        execute(args)
    } catch (t: Throwable) {
        VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, t.toString())
    }

    private fun execute(args: List<String>): VehicleDispatchResult {
        val context = systemContext()
        val display = displayId(context)
        return when (args.firstOrNull()) {
            "screen" -> screen(context, display, args.getOrNull(1))
            "open" -> open(display, args.drop(1))
            "close" -> close(display, args.getOrNull(1))
            "key" -> key(display, args.getOrNull(1))
            "status" -> status(display)
            else -> invalid("expected: $NAME screen|open|close|key|status")
        }
    }

    private fun screen(context: Context, display: Int, state: String?): VehicleDispatchResult {
        val method = when (state) {
            "off" -> "turnBacklightOff"
            "on" -> "turnBacklightOn"
            else -> return invalid("expected: $NAME screen off|on")
        }
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        manager.javaClass.getMethod(method, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(manager, display, SystemClock.uptimeMillis())
        return VehicleDispatchResult.Success("screen=$state display=$display")
    }

    private fun open(display: Int, args: List<String>): VehicleDispatchResult {
        val user = args.getOrNull(0)?.toIntOrNull()
        val pkg = args.getOrNull(1)
        val url = args.getOrNull(2)
        if (user == null || (user != 0 && user != PASSENGER_USER)) return invalid("the user must be 0 or $PASSENGER_USER")
        if (pkg == null || !PassengerScreenParsing.isSafePackage(pkg)) return invalid("not a package name")
        if (url != null && !PassengerScreenParsing.isSafeUrl(url)) return invalid("not a plain https link")

        val start = mutableListOf("/system/bin/am", "start", "--user", user.toString(), "--display", display.toString())
        if (url != null) {
            start += listOf("-a", "android.intent.action.VIEW", "-d", url, "-p", pkg)
        } else {
            val component = launcherActivity(user, pkg)
                ?: return VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, "$pkg has no screen to open for user $user")
            start += listOf("-n", component)
        }
        exec(start)
        var focus = waitForFocus(display, pkg, FIRST_WAIT_MS)
        if (focus == null) {
            // Some apps open on the main display whatever was asked (YouTube starts its real screen from a link
            // handler that ignores the display): move the task the app now has over to the passenger screen.
            val dump = exec(listOf("/system/bin/dumpsys", "activity", "activities"))
            PassengerScreenParsing.rootTaskIdOf(dump, pkg)?.let { task ->
                exec(listOf("/system/bin/am", "display", "move-stack", task.toString(), display.toString()))
                focus = waitForFocus(display, pkg, SHOW_WAIT_MS)
            }
        }
        if (focus == null) {
            // Last resort: start it again as a new task, which is placed on the display that was asked for.
            exec(start + listOf("--activity-clear-task"))
            focus = waitForFocus(display, pkg, SHOW_WAIT_MS)
        }
        return if (focus != null) {
            VehicleDispatchResult.Success("opened=$pkg user=${focus.user} display=$display")
        } else {
            VehicleDispatchResult.Failure(
                VehicleDispatchError.INVOCATION_FAILED,
                "$pkg did not come up on the passenger screen" + (currentTop(display)?.let { " (it shows ${it.packageName})" } ?: ""),
            )
        }
    }

    private fun close(display: Int, only: String?): VehicleDispatchResult {
        if (only != null && !PassengerScreenParsing.isSafePackage(only)) return invalid("not a package name")
        val open = PassengerScreenParsing.appsOn(exec(listOf("/system/bin/dumpsys", "activity", "activities")), display)
            .filter { it.packageName != SAFE_LAUNCHER_PACKAGE }
        val targets = if (only == null) open else open.filter { it.packageName == only }
        if (targets.isEmpty()) {
            return VehicleDispatchResult.Success("closed= note=nothing was open" + (only?.let { " ($it)" } ?: ""))
        }
        val closed = mutableListOf<String>()
        val kept = mutableListOf<String>()
        for (app in targets) {
            when {
                !PassengerScreenParsing.mayClose(app.packageName) -> kept += app.packageName
                // Stopping this app would end the conversation that asked: send it to the launcher instead.
                app.packageName.startsWith(OWN_PACKAGE_PREFIX) -> {
                    exec(listOf("/system/bin/input", "-d", display.toString(), "keyevent", "KEYCODE_HOME"))
                    closed += app.packageName
                }
                else -> {
                    exec(listOf("/system/bin/am", "force-stop", "--user", app.user.toString(), app.packageName))
                    closed += app.packageName
                }
            }
        }
        return VehicleDispatchResult.Success("closed=${closed.joinToString(",")} kept=${kept.joinToString(",")}")
    }

    private fun key(display: Int, name: String?): VehicleDispatchResult {
        val code = when (name) {
            "home" -> "KEYCODE_HOME"
            "back" -> "KEYCODE_BACK"
            else -> return invalid("expected: $NAME key home|back")
        }
        exec(listOf("/system/bin/input", "-d", display.toString(), "keyevent", code))
        return VehicleDispatchResult.Success("key=$name display=$display")
    }

    private fun status(display: Int): VehicleDispatchResult {
        val top = currentTop(display)
        val open = PassengerScreenParsing.appsOn(exec(listOf("/system/bin/dumpsys", "activity", "activities")), display)
            .map { it.packageName }.filter { it != SAFE_LAUNCHER_PACKAGE }
        return VehicleDispatchResult.Success(
            "top=${top?.packageName ?: "none"} open=${open.joinToString(",")} display=$display",
        )
    }

    private fun currentTop(display: Int): DisplayFocus? =
        PassengerScreenParsing.focusOf(exec(listOf("/system/bin/dumpsys", "window", "displays")), display)

    private fun waitForFocus(display: Int, pkg: String, waitMs: Long): DisplayFocus? {
        val deadline = SystemClock.uptimeMillis() + waitMs
        while (true) {
            Thread.sleep(POLL_MS)
            currentTop(display)?.takeIf { it.packageName == pkg }?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) return null
        }
    }

    /** `package/activity` of the app's launcher entry for [user], from the package manager's own resolver. */
    private fun launcherActivity(user: Int, pkg: String): String? {
        val answer = exec(
            listOf(
                "/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", user.toString(),
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg,
            ),
        )
        return answer.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("$pkg/") }
    }

    private fun displayId(context: Context): Int {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return manager.displays.firstOrNull { it.name == DISPLAY_NAME }?.displayId ?: FALLBACK_DISPLAY_ID
    }

    /** Runs a program without a shell and returns what it printed (stdout and stderr), or "" when it timed out. */
    private fun exec(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } } }
        reader.start()
        // Process.waitFor(timeout) needs API 26: wait on a thread instead.
        val waiter = Thread { runCatching { process.waitFor() } }
        waiter.start()
        waiter.join(COMMAND_TIMEOUT_MS)
        if (waiter.isAlive) {
            process.destroy()
            return ""
        }
        reader.join(1_000L)
        return out.toString()
    }

    private fun invalid(detail: String) = VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, detail)

    /** The helper this runs in has already started the system context: reuse it. */
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }
}

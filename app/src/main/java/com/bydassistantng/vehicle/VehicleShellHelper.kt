package com.bydassistantng.vehicle

import android.content.Context
import android.os.Looper
import androidx.annotation.Keep
import kotlinx.coroutines.runBlocking

/**
 * Entry point of a helper *process* that makes the actual HAL call, launched by
 * [ShellHelperVehicleController] through the app's loopback ADB connection:
 *
 *     CLASSPATH=<this app's APK> app_process /system/bin com.bydassistantng.vehicle.VehicleShellHelper <commandId> <value>
 *
 * Why a separate process: the car's HAL guards every device call with `BYDAUTO_*_GET/SET`
 * permissions that are signature-level, so an ordinary app process is refused ("[getInt] permission
 * deny!") however many times it asks, and `pm grant` won't hand them out. The ADB shell identity
 * (uid 2000) *is* accepted — checked on a DiLink head unit with the same read from both identities — which
 * is also how other third-party tools on these head units drive the car without root.
 *
 * Because it's a separate process reachable by anything holding ADB, it re-checks everything itself
 * rather than trusting the app: the command must be in the denylist-filtered registry, its value
 * must be one the registry allows, and the dispatch-time safety gate still runs.
 *
 * `@Keep` because nothing references this class by code, only by name from the shell command line,
 * so a minified build would otherwise strip it.
 */
@Keep
object VehicleShellHelper {
    /** `--serve` keeps the process alive and reads one `<commandId> <value>` per stdin line, so the
     * ~1s JVM + system-context start-up is paid once, not on every command. Without it: one command
     * from the arguments, then exit. */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--serve") serve() else runOnce(args)
        // The system context starts non-daemon threads that would otherwise keep this process alive.
        System.exit(0)
    }

    private fun runOnce(args: Array<String>) {
        val result = try {
            execute(args.toList()) { newController() }
        } catch (t: Throwable) {
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, t.toString())
        }
        emit(HelperProtocol.encode(result))
    }

    private fun serve() {
        try {
            val controller = newController()
            emit(HelperProtocol.READY_LINE)
            val input = System.`in`.bufferedReader()
            // Ends when the app closes its end of the ADB stream (or dies).
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) continue
                val result = try {
                    execute(line.trim().split(' ')) { controller }
                } catch (t: Throwable) {
                    VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, t.toString())
                }
                emit(HelperProtocol.encode(result))
            }
        } catch (t: Throwable) {
            emit(HelperProtocol.encode(VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, "helper failed: $t")))
        }
    }

    private fun emit(line: String) {
        println(line)
        System.out.flush()
    }

    private fun newController(): ReflectionVehicleController {
        Looper.prepareMainLooper()
        return ReflectionVehicleController(systemContext())
    }

    private fun execute(args: List<String>, controller: () -> ReflectionVehicleController): VehicleDispatchResult {
        // Read-only questions: "query <id>". Only the queries in VehicleQuery exist; nothing else is readable this way.
        if (args.firstOrNull() == "query") {
            val query = args.getOrNull(1)?.let { VehicleQuery.byId(it) } ?: return invalid("unknown query '${args.getOrNull(1)}'")
            return runBlocking { controller().query(query) }
        }
        if (args.size != 2) return invalid("expected <commandId> <value>")
        val command = VehicleCommandRegistry.byId(args[0]) ?: return invalid("unknown or blocked command '${args[0]}'")
        val value = args[1].toIntOrNull() ?: return invalid("value '${args[1]}' is not an integer")
        if (!isAllowedValue(command, value)) return invalid("value $value is not allowed for ${command.id}")

        return try {
            runBlocking { controller().dispatch(command, value) }
        } catch (e: IllegalStateException) {
            // VehicleSafety.assertDispatchAllowed rejecting a blocked domain.
            VehicleDispatchResult.Blocked(e.message ?: "blocked")
        }
    }

    private fun invalid(detail: String) = VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, detail)

    private fun isAllowedValue(command: VehicleCommand, value: Int): Boolean = when (val parameter = command.parameter) {
        is VehicleParameter.FixedEnum -> value in parameter.options.values
        is VehicleParameter.Range -> value in parameter.min..parameter.max
    }

    /** The HAL classes want a Context; a bare `app_process` has none, so borrow the system one. */
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }
}

package com.bydassistantng.vehicle

import android.content.Context
import com.bydassistantng.util.AdbHelper
import com.bydassistantng.util.AppLogger
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShellHelperVehicle"
private const val HELPER_CLASS = "com.bydassistantng.vehicle.VehicleShellHelper"

// Opening the connection and starting the helper JVM: ~1–2s. Generous, since ADB may have to reconnect.
private const val CONNECT_TIMEOUT_MS = 8_000L
private const val START_TIMEOUT_MS = 10_000L
// One command in a running helper: the HAL call, plus up to ~1.5s of A/C read-back polling.
private const val COMMAND_TIMEOUT_MS = 8_000L
// A helper nobody has used for this long is shut down; the next press starts a fresh one.
private const val IDLE_CLOSE_MS = 5 * 60_000L

private val SAFE_COMMAND_ID = Regex("[A-Za-z0-9_.]+")

/**
 * The real vehicle controller: sends each command to a helper process running under the ADB shell
 * identity, since the car's HAL refuses the app's own process (see [VehicleShellHelper] for why).
 *
 * The helper stays alive between commands (`--serve`), reached over one long-lived ADB stream, so a
 * command costs milliseconds rather than the ~1.3s a cold JVM start took. [warmUp] starts it ahead of
 * time — it's called when a voice turn begins, so it's ready by the time the user finishes speaking.
 * If the stream dies it is simply restarted, once, for the command in flight.
 *
 * Success only means the helper reports the call accepted (and, for A/C, confirmed by read-back).
 */
@Singleton
class ShellHelperVehicleController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VehicleController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var session: HelperSession? = null
    private var idleJob: Job? = null

    /** Starts the helper in the background if it isn't running. Never blocks or throws. */
    fun warmUp() {
        scope.launch {
            mutex.withLock {
                runCatching { ensureSession() }.onFailure { AppLogger.logError(TAG, "Helper warm-up failed", it) }
                armIdleClose()
            }
        }
    }

    override suspend fun dispatch(command: VehicleCommand, value: Int): VehicleDispatchResult {
        VehicleSafety.assertDispatchAllowed(command)
        // The id and value end up on a helper's stdin; both come from the fixed registry and an int,
        // but this is cheap insurance against that ever changing.
        if (!SAFE_COMMAND_ID.matches(command.id)) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "Unsafe command id '${command.id}'")
        }

        return mutex.withLock {
            try {
                // One retry, and only when the stream was found dead: a command is never re-sent after
                // the helper already answered it.
                send(command, value) ?: run {
                    AppLogger.log(TAG, "Helper connection was lost — restarting it")
                    closeSession()
                    send(command, value)
                } ?: VehicleDispatchResult.Failure(
                    VehicleDispatchError.HELPER_UNAVAILABLE,
                    "Could not reach the head unit's ADB connection — ADB debugging must be on and authorized.",
                )
            } finally {
                armIdleClose()
            }
        }
    }

    /** @return null if the helper couldn't be started or its stream was dead; a result otherwise. */
    private suspend fun send(command: VehicleCommand, value: Int): VehicleDispatchResult? {
        val helper = ensureSession() ?: return null
        AppLogger.log(TAG, "Helper: ${command.id}=$value")
        val reply = try {
            helper.write("${command.id} $value")
            helper.readLineStartingWith(HelperProtocol.RESULT_PREFIX.trim(), COMMAND_TIMEOUT_MS)
        } catch (e: IOException) {
            null
        }
        if (reply == null) {
            closeSession()
            return null
        }
        return HelperProtocol.parse(reply)
            ?: VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, "Unreadable helper reply: ${reply.take(200)}")
    }

    private suspend fun ensureSession(): HelperSession? {
        session?.let { return it }
        val started = System.currentTimeMillis()
        val dadb = AdbHelper.connect(context, CONNECT_TIMEOUT_MS, socketTimeoutMs = 0) ?: return null
        val apk = context.applicationInfo.sourceDir
        val helper = try {
            HelperSession(dadb, dadb.openShell("CLASSPATH='$apk' exec app_process /system/bin $HELPER_CLASS --serve 2>&1"))
        } catch (e: Exception) {
            runCatching { dadb.close() }
            AppLogger.logError(TAG, "Could not start the helper", e)
            return null
        }
        if (helper.readLineStartingWith(HelperProtocol.READY_LINE, START_TIMEOUT_MS) == null) {
            AppLogger.logError(TAG, "The helper never reported ready")
            helper.close()
            return null
        }
        AppLogger.log(TAG, "Helper ready in ${System.currentTimeMillis() - started}ms")
        session = helper
        return helper
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun armIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            mutex.withLock {
                AppLogger.log(TAG, "Helper idle — shutting it down")
                closeSession()
            }
        }
    }
}

/** One running helper process and the ADB stream wired to its stdin/stdout. */
private class HelperSession(private val dadb: Dadb, private val stream: AdbShellStream) {
    private val buffer = StringBuilder()
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun write(line: String) = stream.write(line + "\n")

    /**
     * Reads helper output until a line starting with [prefix] arrives. Returns null if the stream
     * ends, fails, or [timeoutMs] passes. A blocking socket read can't be interrupted, so the timeout
     * works by closing the whole session from a watchdog, which makes the read throw.
     */
    suspend fun readLineStartingWith(prefix: String, timeoutMs: Long): String? {
        val watchdog = watchdogScope.launch {
            delay(timeoutMs)
            close()
        }
        return try {
            withContext(Dispatchers.IO) { runInterruptible { readBlocking(prefix) } }
        } catch (e: IOException) {
            null
        } finally {
            watchdog.cancel()
        }
    }

    private fun readBlocking(prefix: String): String? {
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline >= 0) {
                val line = buffer.substring(0, newline).trim()
                buffer.delete(0, newline + 1)
                if (line.startsWith(prefix)) return line
                continue
            }
            val packet = stream.read()
            if (packet is AdbShellPacket.Exit) return null
            buffer.append(String(packet.payload, Charsets.UTF_8))
        }
    }

    fun close() {
        runCatching { stream.close() }
        runCatching { dadb.close() }
    }
}

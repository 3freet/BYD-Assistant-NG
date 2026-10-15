package com.bydassistantng.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The head unit's own voice assistant (BYD's `com.byd.autovoice`), which by default answers the steering-wheel
 * microphone button. It runs next to this app, so one press can start both. It can be switched off, and back on,
 * for the main user from the loopback ADB connection the app already uses: nothing is uninstalled.
 */
object CarVoiceAssistant {
    const val PACKAGE = "com.byd.autovoice"

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val SETTLE_MS = 3_000L
    private const val POLL_MS = 150L

    enum class State { ABSENT, ON, OFF }

    /** Whether the assistant is on this unit, and if so whether it is enabled. */
    fun state(context: Context): State = try {
        if (context.packageManager.getApplicationInfo(PACKAGE, 0).enabled) State.ON else State.OFF
    } catch (e: PackageManager.NameNotFoundException) {
        State.ABSENT
    }

    /** The shell command for [on]; `pm disable-user` keeps it a reversible, per-user setting. */
    internal fun command(on: Boolean): String =
        if (on) "pm enable --user 0 $PACKAGE" else "pm disable-user --user 0 $PACKAGE"

    /** Switches it on or off. Blocks for a few seconds; true once the state is what was asked for. */
    suspend fun set(context: Context, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val adb = AdbHelper.connect(context, CONNECT_TIMEOUT_MS) ?: return@withContext false
        try {
            adb.shell(command(on))
            val wanted = if (on) State.ON else State.OFF
            val deadline = SystemClock.elapsedRealtime() + SETTLE_MS
            var reached = state(context) == wanted
            while (!reached && SystemClock.elapsedRealtime() < deadline) {
                delay(POLL_MS)
                reached = state(context) == wanted
            }
            reached
        } catch (e: Throwable) {
            false
        } finally {
            runCatching { adb.close() }
        }
    }
}

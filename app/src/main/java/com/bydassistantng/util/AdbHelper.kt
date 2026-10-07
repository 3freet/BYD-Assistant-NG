package com.bydassistantng.util

import android.content.Context
import android.util.Log
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "AdbHelper"

/**
 * Local-ADB helper. This is one of the app's two uses of `dev.mobile:dadb` (the other is the vehicle
 * helper process): granting itself a missing permission
 * (surfaced from a HAL `SecurityException`) via `pm grant` over a loopback ADB connection the user
 * has already enabled on the head unit, with no root or process-fork assumptions.
 */
object AdbHelper {

    /** `pm grant <ourPackage> <permission>` over ADB. [timeoutMs] should be short for a retry
     * attempted synchronously mid-voice-turn (a dead ADB connection must fail fast, not stall the
     * turn), and can be longer for an onboarding flow with its own "connecting…" UI. */
    suspend fun requestPermission(context: Context, permission: String, timeoutMs: Long = 20_000L) {
        adbShell(context, "pm grant ${context.packageName} $permission", timeoutMs)
    }

    /** Opens a loopback ADB connection, retrying until [timeoutMs]; null if none could be made. The
     * caller owns it and must close it. [socketTimeoutMs] of 0 means reads never time out — required
     * for a connection that stays open and idle between commands, which a timeout would tear down. */
    suspend fun connect(context: Context, timeoutMs: Long, socketTimeoutMs: Int = 10_000): Dadb? = withContext(Dispatchers.IO) {
        setupUserHome(context)
        withTimeoutOrNull(timeoutMs.milliseconds) {
            var connected: Dadb? = null
            while (isActive && connected == null) {
                connected = try {
                    Dadb.discover(connectTimeout = 2000, socketTimeout = socketTimeoutMs)
                } catch (e: Throwable) {
                    null
                }
                if (connected == null) delay(150L.milliseconds)
            }
            connected
        }
    }

    private suspend fun adbShell(context: Context, cmd: String, timeoutMs: Long = 20_000L) {
        withContext(Dispatchers.IO) {
            setupUserHome(context)
            val result = withTimeoutOrNull(timeoutMs.milliseconds) {
                while (isActive) {
                    try {
                        var dAdb = Dadb.discover(connectTimeout = 2000, socketTimeout = 5000)
                        dAdb?.shell("echo 'init'") ?: throw Throwable("fail to connect adb")
                        dAdb.close()

                        dAdb = Dadb.discover(connectTimeout = 2000, socketTimeout = 5000)
                        dAdb?.shell(cmd)
                        dAdb?.close()
                        return@withTimeoutOrNull true
                    } catch (e: Throwable) {
                        delay(100L.milliseconds)
                    }
                }
                false
            }

            if (result != true) {
                Log.w(TAG, "ADB connection failed for command: $cmd")
            }
        }
    }

    /** dadb requires a non-empty `user.home` system property to create its adbkey files under. */
    private fun setupUserHome(context: Context) {
        val userHome = System.getProperty("user.home") ?: ""
        if (userHome.isEmpty()) {
            System.setProperty("user.home", context.filesDir.absolutePath)
        }
    }
}

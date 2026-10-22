package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.bydassistantng.util.AdbAccess
import com.bydassistantng.util.AppLogger
import dadb.Dadb
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Makes the car show its "Allow USB debugging?" prompt, as it does for a fresh install: the connection is made with a
 * throwaway ADB key, through the same [AdbAccess] the app uses, and waits for the answer. Leave the prompt unanswered
 * to see that it stays up for the whole wait (then the key is simply never allowed); answer Allow to see it come out
 * allowed (tick "always" only if a key left on the car does not matter).
 *
 *     am broadcast -a com.bydassistantng.debug.ADB_PROMPT -n <pkg>/com.bydassistantng.debug.DebugAdbPromptReceiver
 *
 * The result is in the app log as "AdbPrompt: ...", with the lines [AdbAccess] writes. The app's own key is put back
 * afterwards; do not use ADB from the app while this runs, it would use the throwaway key too. Only exists in debug builds.
 */
class DebugAdbPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                run(app)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun run(context: Context) {
        val tag = "AdbPrompt"
        val home = File(context.cacheDir, "adbprompt-${System.currentTimeMillis()}").apply { mkdirs() }
        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            val access = AdbAccess(
                tryOnce = {
                    val dadb = try {
                        Dadb.discover(connectTimeout = 2000, socketTimeout = AdbAccess.ANSWER_WAIT_MS.toInt())
                    } catch (e: Throwable) {
                        null
                    }
                    dadb?.close()
                    dadb != null
                },
                now = { SystemClock.elapsedRealtime() },
                pause = { delay(it) },
                log = { AppLogger.log(tag, it) },
            )
            val started = SystemClock.elapsedRealtime()
            AppLogger.log(tag, "Asking with a throwaway key")
            val allowed = runBlocking { access.await(8_000) }
            AppLogger.log(tag, "Result after ${(SystemClock.elapsedRealtime() - started) / 1000.0} s: allowed=$allowed")
        } finally {
            if (previousHome != null) System.setProperty("user.home", previousHome) else System.clearProperty("user.home")
            home.deleteRecursively()
        }
    }
}

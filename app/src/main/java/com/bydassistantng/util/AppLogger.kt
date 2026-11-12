package com.bydassistantng.util

import android.content.Context
import android.util.Log
import com.bydassistantng.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * General-purpose persistent diagnostic log — separate from [CrashLogger], which only records
 * uncaught exceptions. This captures the normal, non-crashing request/response lifecycle: Gemini
 * request timing plus full HTTP status/response bodies, audio recording stats, and per-turn stage
 * timing. That's the detail needed to diagnose a "it's slow" or "Gemini returned an error" report
 * where nothing actually crashed, so [CrashLogger] never fires, and logcat isn't a reliable option
 * on a head unit whose users won't have adb attached.
 *
 * Installed once (see [install], called from `AssistantApp.onCreate`) rather than threaded through
 * every constructor, since call sites like [com.bydassistantng.gemini.GeminiClient] have no other
 * reason to hold a `Context` — matching the same static-utility shape [CrashLogger] already uses.
 */
object AppLogger {
    private const val LOG_FILE_NAME = "app_log.txt"
    private const val MAX_FILE_SIZE_BYTES = 1024 * 1024
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        if (!BuildConfig.DEBUG) removeOldMirror(context)
    }

    /** Earlier builds mirrored the raw log into the external files dir in every build type; remove what they left there. */
    private fun removeOldMirror(context: Context) {
        try {
            context.getExternalFilesDir(null)?.let { File(it, LOG_FILE_NAME).delete() }
        } catch (_: Throwable) {
            // Not worth a failure at start-up: the next start tries again.
        }
    }

    fun log(tag: String, message: String) {
        Log.d(tag, message)
        appendLine(tag, message)
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val full = if (throwable != null) "$message\n${throwable.fullStackTrace()}" else message
        appendLine(tag, full)
    }

    // @Synchronized: this is called from the main, IO and Default dispatchers at once, and both the
    // shared SimpleDateFormat (not thread-safe) and the append/trim of the file need serializing —
    // otherwise concurrent lines can interleave or corrupt each other's timestamps.
    @Synchronized
    private fun appendLine(tag: String, message: String) {
        val context = appContext ?: return
        val line = "${timestampFormat.format(Date())} [$tag] $message\n"
        try {
            appendCapped(logFile(context), line)
        } catch (_: Throwable) {
            // Logging must never be the reason something else breaks.
        }
        // A debug build, the one a developer runs on the car, also mirrors the line into the app's external files dir, which
        // — unlike the private copy above, and unlike logcat on this head unit — `adb pull
        // /sdcard/Android/data/<pkg>/files/app_log.txt` can read without the app being debuggable. The published builds
        // do not: what is in this file is the raw log (what was said, places), the masking only happens when the log is
        // shown, shared or saved, and a file in shared storage can be read by anyone who plugs the unit into a computer
        // and, on older Android versions, by other apps. The in-app viewer reads the private file in every build.
        if (BuildConfig.DEBUG) {
            try {
                context.getExternalFilesDir(null)?.let { appendCapped(File(it, LOG_FILE_NAME), line) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun appendCapped(file: File, line: String) {
        if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) trimFile(file)
        file.appendText(line)
    }

    /** Drops the oldest half rather than deleting outright, so a diagnosis in progress doesn't
     * lose all its context right when the file happens to fill up. */
    private fun trimFile(file: File) {
        val lines = file.readLines()
        file.writeText(lines.drop(lines.size / 2).joinToString("\n"))
    }

    fun logFile(context: Context): File = File(context.filesDir, LOG_FILE_NAME)

    fun readLog(context: Context): String =
        logFile(context).takeIf { it.exists() }?.readText()?.ifBlank { null } ?: "No log entries yet."

    fun clear(context: Context) {
        logFile(context).delete()
        context.getExternalFilesDir(null)?.let { File(it, LOG_FILE_NAME).delete() }
    }
}

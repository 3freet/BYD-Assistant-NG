package com.bydassistantng.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists uncaught exceptions to a file that survives process death — necessary because neither the
 * in-app log viewer nor a head unit's system logcat buffer survives a fatal-crash restart. This only records; it never
 * suppresses or alters the actual crash, which still proceeds exactly as it would without this
 * installed.
 */
object CrashLogger {
    private const val LOG_FILE_NAME = "crash_log.txt"
    private const val MAX_FILE_SIZE_BYTES = 512 * 1024

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                appendCrash(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Logging must never be the reason the real crash doesn't get reported.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun appendCrash(context: Context, thread: Thread, throwable: Throwable) {
        val file = logFile(context)
        if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) file.delete()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("=== $timestamp — thread \"${thread.name}\" ===\n")
            append(throwable.stackTraceToString())
            append("\n\n")
        }
        file.appendText(entry)
    }

    fun logFile(context: Context): File = File(context.filesDir, LOG_FILE_NAME)

    fun readLog(context: Context): String =
        logFile(context).takeIf { it.exists() }?.readText()?.ifBlank { null } ?: "No crashes logged yet."

    fun clear(context: Context) {
        logFile(context).delete()
    }
}

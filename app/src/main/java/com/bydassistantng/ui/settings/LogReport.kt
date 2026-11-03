package com.bydassistantng.ui.settings

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.bydassistantng.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A log as it leaves the head unit: shared as text, or saved as a file in Downloads (which a file manager, a USB stick or
 * `adb pull` can reach). Both carry a short header saying which app and car software the log is from, and what the vehicle
 * helper last read of the car (see [com.bydassistantng.vehicle.PlatformState.carLine]), since a log that has run for days may
 * have lost the lines at its start that said so.
 */
internal object LogReport {
    /**
     * What the header says about the log being [masked] or not, so a reader knows what they hold, and [carLine], the line the
     * vehicle helper wrote about the car's software the last time it started (null if it has not yet on this firmware).
     */
    fun header(masked: Boolean, carLine: String? = null, now: Date = Date()): String = listOf(
        "BYD Assistant NG ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "Exported ${stamp(now)}",
        if (masked) "Private info: hidden (what was said and asked for, places, keys, addresses and numbers)" else "Private info: NOT hidden - this copy holds what was said and asked for",
        "Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), model ${Build.MODEL}, device ${Build.DEVICE}",
        "Firmware ${Build.DISPLAY}, fingerprint ${Build.FINGERPRINT}",
        carLine?.let { "Car software, as the vehicle helper last read it: $it" } ?: "Car software: the vehicle helper has not started on this firmware yet",
        "----------------------------------------",
    ).joinToString("\n", postfix = "\n")

    /** The text of a report: the header, a note where older lines were left out, then the log. */
    fun text(header: String, kept: LogText.Kept): String =
        header + (if (kept.isCut) "[older lines left out: the last ${kept.text.length} of ${kept.totalChars} characters]\n" else "") + kept.text

    fun fileName(label: String, masked: Boolean, now: Date = Date()): String {
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        return "bydassistant-$label-$time${if (masked) "-masked" else ""}.txt"
    }

    private fun stamp(now: Date): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)

    /**
     * Saves [text] as [name] in the Downloads folder and says where, or null if it could not be. Before Android 10 the
     * Downloads folder needs a permission the app does not ask for, so the file goes in the app's own folder on shared
     * storage instead (which `adb pull` can reach as well).
     */
    fun saveToDownloads(context: Context, name: String, text: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }?.let { return@runCatching "${Environment.DIRECTORY_DOWNLOADS}/$name" }
            }
        }
        val folder = context.getExternalFilesDir(null) ?: return@runCatching null
        File(folder, name).also { it.writeText(text, Charsets.UTF_8) }.absolutePath
    }.getOrNull()
}

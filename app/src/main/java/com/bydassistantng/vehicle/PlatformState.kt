package com.bydassistantng.vehicle

import android.content.Context
import android.os.Build
import com.bydassistantng.BuildConfig
import com.bydassistantng.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app knows of the car's software: which platform it runs, which commands the car cannot do, and the line the vehicle
 * helper wrote about what it saw. The app tells what it can from its own process when it starts; the vehicle helper, which can
 * always read the car's numbers and its table of features, says what it found each time it starts and that is what counts. It
 * is kept between runs (for this firmware only) so that the first conversation after an update already offers only what the
 * car has, even if the app itself cannot read the car's table, and so that a log exported later can still say what the helper saw
 * ([carLine]).
 */
object PlatformState {
    private const val TAG = "Platform"
    private const val PREFS = "platform_state"
    private const val KEY_PLATFORM = "platform"
    private const val KEY_FIRMWARE = "firmware"
    private const val KEY_UNAVAILABLE = "unavailable"
    private const val KEY_REPORT = "report"

    // What the helper saw of the car is long and the same every time: it is written to the log once per run of the app,
    // and again whenever the platform it found, or what the car cannot do, changes.
    @Volatile
    private var detailsLogged = false

    @Volatile
    private var lastReport: String? = null

    /** Called once as the app starts. */
    fun start(context: Context) {
        val detected = PlatformDetector.detect()
        val saved = saved(context)
        val chosen = PlatformDetector.choose(detected, saved?.platform)
        VehicleCommandRegistry.platform = chosen
        saved?.let { VehicleCommandRegistry.useReportedUnavailable(it.unavailable) }
        AppLogger.log(TAG, "App ${BuildConfig.VERSION_NAME}, ${deviceLine()}")
        val how = when {
            detected != DiLinkPlatform.UNKNOWN -> "read from the car's own numbers"
            saved?.platform != null -> "as the vehicle helper reported it last time"
            else -> "not known yet, until the vehicle helper reports"
        }
        AppLogger.log(TAG, "DiLink platform ${PlatformDetector.label(chosen, HeadUnitProperties.read("ro.vehicle.type"))}: $how")
    }

    /** The vehicle helper has started and says which platform it found, and which commands this car cannot do. */
    fun helperReported(context: Context, ready: HelperProtocol.Ready) {
        val before = VehicleCommandRegistry.platform
        val unavailableBefore = VehicleCommandRegistry.unavailable.keys
        VehicleCommandRegistry.platform = ready.platform
        VehicleCommandRegistry.useReportedUnavailable(ready.unavailable)
        val found = PlatformDetector.label(ready.platform, PlatformDetector.vehicleTypeIn(ready.details))
        val line = "Vehicle helper found $found" + (if (before != ready.platform) " (was ${before.label})" else "") + ": ${ready.details}"
        lastReport = "${stamp()} $line"
        if (!detailsLogged || before != ready.platform || unavailableBefore != ready.unavailable) {
            detailsLogged = true
            AppLogger.log(TAG, line)
        }
        remember(context, ready)
    }

    /**
     * What the vehicle helper last said about this car's software, with when, for the header of an exported log: the line in the
     * log itself is written once per run and a log that has run for days may have lost it. Null where no helper has run on this
     * firmware.
     */
    fun carLine(context: Context): String? = lastReport ?: runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_FIRMWARE, null) == Build.FINGERPRINT) prefs.getString(KEY_REPORT, null) else null
    }.getOrNull()

    private fun deviceLine(): String =
        "model=${Build.MODEL} device=${Build.DEVICE} android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}) " +
            "display=${Build.DISPLAY} fingerprint=${Build.FINGERPRINT}"

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private class Saved(val platform: DiLinkPlatform?, val unavailable: Set<String>)

    private fun saved(context: Context): Saved? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_FIRMWARE, null) != Build.FINGERPRINT) return@runCatching null
        Saved(
            DiLinkPlatform.byKey(prefs.getString(KEY_PLATFORM, null)),
            prefs.getString(KEY_UNAVAILABLE, "").orEmpty().split(',').filter { it.isNotEmpty() }.toSet(),
        )
    }.getOrNull()

    private fun remember(context: Context, ready: HelperProtocol.Ready) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                // A platform that was not recognised is not kept: the next run tries again, and until then the app assumes nothing.
                if (ready.platform != DiLinkPlatform.UNKNOWN) putString(KEY_PLATFORM, ready.platform.key) else remove(KEY_PLATFORM)
                putString(KEY_UNAVAILABLE, ready.unavailable.sorted().joinToString(","))
                putString(KEY_FIRMWARE, Build.FINGERPRINT)
                lastReport?.let { putString(KEY_REPORT, it) }
            }.apply()
        }
    }
}

package com.bydassistantng.util

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.os.SystemClock
import com.bydassistantng.BuildConfig
import com.bydassistantng.R
import com.bydassistantng.service.BootReceiver
import com.bydassistantng.service.WheelKeyService
import com.bydassistantng.vehicle.HeadUnitProperties
import kotlinx.coroutines.runBlocking

/**
 * The steering-wheel mic button isn't a standard Android key. On this BYD DiLink unit it arrives
 * from the `simulate-keys` input device (kernel scan codes 290 / 312) and the unit's key layout
 * maps those to BYD-private keycodes, which no `KeyEvent.KEYCODE_*` constant covers. Captured with
 * `getevent` / logcat: a short press is 320, a long press is 328.
 */
private const val TAG = "WheelKeys"
private const val SELF_BIND_GRACE_MS = 1_200L
private const val SELF_BIND_POLL_MS = 100L
private const val REBIND_GAP_MS = 400L
private const val MIN_REBIND_INTERVAL_MS = 30_000L

// DiLink 3.0 only (see [WheelKeys.restartToRebind]).
private const val NUDGE_VERIFY_MS = 3_000L
private const val MIN_SELF_RESTART_INTERVAL_MS = 10 * 60_000L
private const val FORCE_STOP_SETTLE_MS = 5_000L
private const val RESTART_ADB_TIMEOUT_MS = 20_000L
private const val RESTART_PREFS = "wheel_keys"
private const val KEY_LAST_RESTART_AT = "last_self_restart_elapsed"
// Debug builds only: `adb shell setprop debug.bydassistant.dilink30 1` makes an emulator count as a DiLink 3.0 unit, so the restart can be
// tried there. A release build never reads it.
private const val DEBUG_DILINK30_PROPERTY = "debug.bydassistant.dilink30"

object WheelKeys {
    const val MIC_SHORT_PRESS = 320
    const val MIC_LONG_PRESS = 328

    // Keys that must never be taken over — remapping one would break navigation on a head unit
    // that has no other way back.
    private val RESERVED = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER)

    fun isReserved(keyCode: Int): Boolean = keyCode in RESERVED

    /** A name for [keyCode] in the app language. */
    fun describe(context: Context, keyCode: Int): String = when (keyCode) {
        MIC_SHORT_PRESS -> AppLanguage.string(context, R.string.key_mic_short, keyCode)
        MIC_LONG_PRESS -> AppLanguage.string(context, R.string.key_mic_long, keyCode)
        else -> {
            val name = KeyEvent.keyCodeToString(keyCode)
            // Unknown codes come back as their bare number.
            if (name.all { it.isDigit() }) AppLanguage.string(context, R.string.key_generic, keyCode)
            else AppLanguage.string(context, R.string.key_named, name.removePrefix("KEYCODE_"), keyCode)
        }
    }

    /** Whether the user has switched [WheelKeyService] on in Android's accessibility settings —
     * read from the setting itself rather than the service's own state, so it is right even before
     * the service has been bound. */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val self = ComponentName(context, WheelKeyService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
    }

    /**
     * Switches the service on from inside the app. This head unit has no Accessibility settings
     * screen at all (`android.settings.ACCESSIBILITY_SETTINGS` resolves to nothing), so the usual
     * "open settings and tick the box" can't work. Instead the app grants itself
     * WRITE_SECURE_SETTINGS over the loopback ADB connection it already uses for vehicle
     * permissions, then adds itself to the enabled-services list directly — the same route the
     * button-mapper apps already installed on the car take.
     *
     * @return false if it couldn't be done, almost always because ADB debugging isn't reachable.
     */
    suspend fun enableService(context: Context): Boolean {
        if (isServiceEnabled(context)) return true

        val permission = Manifest.permission.WRITE_SECURE_SETTINGS
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) AdbHelper.requestPermission(context, permission)
        return ensureServiceEnabled(context)
    }

    private var lastRebindAtMs = 0L

    /**
     * Makes sure the service is not just *enabled* but actually *running*, which are two different
     * things on this head unit. Its quick-boot step force-stops every app when the car sleeps; on wake
     * the entry can still be in `enabled_accessibility_services`, yet the system never binds it again
     * (it only does so when that setting changes) — so the app can be started by a boot broadcast, or
     * opened by hand, and the wheel button stays dead. Observed on a DiLink head unit: removing and re-adding
     * the entry makes the system start the service within a second.
     *
     * [atWake] is true when the car has just woken (a boot broadcast, not an app update). On a DiLink 3.0 head unit the nudge
     * then gets a few seconds to work, and when it has not, the app restarts itself to free the service ([restartToRebind]).
     * Every other head unit is left as it was.
     *
     * Blocks for a few seconds — call it from a background thread.
     *
     * @return true if the service is running afterwards.
     */
    @Synchronized
    fun ensureServiceRunning(context: Context, atWake: Boolean = false): Boolean {
        if (!ensureServiceEnabled(context)) return false // missing from the list: adding it makes the system bind it
        // When our process was started *by* the system binding the service, it is about to connect on its own.
        if (awaitService(SELF_BIND_GRACE_MS)) return true
        val nudged = rebindService(context)
        if (!atWake || !isDiLink30Unit()) return nudged
        if (awaitService(NUDGE_VERIFY_MS)) return true
        restartToRebind(context)
        return false
    }

    /** Waits up to [ms] for the service to connect; true when it is connected. */
    private fun awaitService(ms: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ms
        while (WheelKeyService.instance == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(SELF_BIND_POLL_MS)
        return WheelKeyService.instance != null
    }

    // ── DiLink 3.0: restarting the app to free the service ──────────────────────────────────

    /** The broadcast the app is started with again after [restartToRebind] stopped it (handled by [BootReceiver]). */
    const val ACTION_RESTARTED = "com.bydassistantng.action.RESTARTED_FOR_WHEEL_BUTTON"

    /**
     * DiLink 3.0 only: frees a service the system no longer binds, by restarting the app.
     *
     * When the car kills the app as it goes to sleep, Android moves the service to its "binding" set (switched on, not bound,
     * waiting for the service to be restarted), and whenever the enabled list changes it skips everything in that set, so the
     * nudge cannot reach it. On a DiLink 3.0 unit (Android 10) that restart mostly never comes: an owner's log (2026-11-26)
     * shows the service bound in 21 of 81 runs of the app, the nudge binding it 0 times in 60, and the car's own assistant
     * answering the button the rest of the time. Only a force-stop (or an update) empties that set, which is why opening the
     * app after it had been stopped brought the button back every time (9 of 9).
     *
     * So the app does the same itself: the ADB shell force-stops it and starts it again with [ACTION_RESTARTED], and the new
     * process puts the service back on the list ([afterRestart]) and carries on with the wake. Not more than once in
     * [MIN_SELF_RESTART_INTERVAL_MS], never while the app is on the screen, and not when the car's "Allow USB debugging?"
     * prompt was refused.
     */
    private fun restartToRebind(context: Context) {
        val prefs = context.getSharedPreferences(RESTART_PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val decision = restartDecision(now, prefs.getLong(KEY_LAST_RESTART_AT, 0L), AdbHelper.isRefused, isAppOnScreen())
        if (decision != RestartDecision.RESTART) {
            AppLogger.log(TAG, decision.message)
            return
        }
        // Written before the restart is asked for: the process that writes it is about to be stopped.
        prefs.edit().putLong(KEY_LAST_RESTART_AT, now).commit()
        AppLogger.log(TAG, "Wheel button service is still not bound after the nudge — restarting the app to free it (DiLink 3.0)")
        val sent = runCatching { runBlocking { AdbHelper.shell(context, restartCommand(context.packageName), RESTART_ADB_TIMEOUT_MS) } }
            .getOrDefault(false)
        if (!sent) AppLogger.log(TAG, "Could not reach ADB to restart the app — the wheel button stays off until the app is opened")
    }

    /**
     * In the process that [restartToRebind] started: waits for the force-stop to take the service off the enabled list (that is
     * what empties the "binding" set, and adding it back before the system's own write would let that write undo it), puts it
     * back, and says whether it came back.
     */
    fun afterRestart(context: Context) {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + FORCE_STOP_SETTLE_MS
        while (isServiceEnabled(context) && WheelKeyService.instance == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(SELF_BIND_POLL_MS)
        }
        ensureServiceRunning(context)
        val back = awaitService(NUDGE_VERIFY_MS)
        AppLogger.log(
            TAG,
            if (back) "Wheel button service is back after the restart (${SystemClock.elapsedRealtime() - started} ms)"
            else "Wheel button service is still not bound after the restart",
        )
    }

    internal enum class RestartDecision(val message: String) {
        RESTART(""),
        ON_SCREEN("Wheel button service is still not bound; the app is on the screen, so it is not restarted now"),
        ADB_REFUSED("Wheel button service is still not bound; restarting the app needs ADB, and the car's prompt was refused"),
        TOO_SOON("Wheel button service is still not bound, but the app was restarted for it less than 10 minutes ago — not again"),
    }

    /** Whether to restart the app now; [lastRestartAt] and [now] are `elapsedRealtime`, which a wake (a quick-boot) does not reset. */
    internal fun restartDecision(now: Long, lastRestartAt: Long, adbRefused: Boolean, appOnScreen: Boolean): RestartDecision = when {
        appOnScreen -> RestartDecision.ON_SCREEN
        adbRefused -> RestartDecision.ADB_REFUSED
        // A time ahead of now was written before a real reboot, which does reset the clock.
        lastRestartAt > 0L && now - lastRestartAt in 0 until MIN_SELF_RESTART_INTERVAL_MS -> RestartDecision.TOO_SOON
        else -> RestartDecision.RESTART
    }

    /**
     * The shell command that restarts the app. It runs in a session of its own (`setsid`), so it outlives this process, whose ADB
     * connection the force-stop cuts; the first second lets the command return before that. The force-stop takes the service off
     * the list and empties the "binding" set; the broadcast, which is allowed to start a stopped app (`0x20`), starts the app again.
     */
    internal fun restartCommand(packageName: String): String {
        val receiver = "$packageName/${BootReceiver::class.java.name}"
        val steps = "sleep 1; am force-stop $packageName; sleep 2; am broadcast -f 0x20 -a $ACTION_RESTARTED -n $receiver"
        return "setsid sh -c '$steps' > /dev/null 2>&1 < /dev/null &"
    }

    /** Whether this head unit is a DiLink 3.0 one, by its own name (see [isDiLink30]). */
    private fun isDiLink30Unit(): Boolean =
        isDiLink30(Build.DEVICE, Build.MODEL, HeadUnitProperties.read("ro.vehicle.type")) ||
            (BuildConfig.DEBUG && HeadUnitProperties.read(DEBUG_DILINK30_PROPERTY) == "1")

    private val DILINK_30_NAME = Regex("^DiLink[ _-]?3\\.0(?!\\d)", RegexOption.IGNORE_CASE)
    private val DI_30_TYPE = Regex("^Di3\\.0(?!\\d)", RegexOption.IGNORE_CASE)

    /**
     * A DiLink 3.0 head unit by its own name: `DiLink3.0` as its device or model (`DiLink3.0 For BYD AUTO`), or `Di3.0` in
     * `ro.vehicle.type` (`Di3.0_3.5UI`). Not the platform the app reads from the car's numbers: the DiLink 3.0 unit in that log
     * has the newer numbers, which read as DiLink 4. A DiLink 3.0 unit that runs the 5.0 interface (`DiLink50_5.0UI`) still
     * says DiLink3.0 as its device.
     */
    internal fun isDiLink30(device: String?, model: String?, vehicleType: String?): Boolean =
        listOf(device, model).any { DILINK_30_NAME.containsMatchIn(it.orEmpty().trim()) } ||
            DI_30_TYPE.containsMatchIn(vehicleType.orEmpty().trim())

    /** Whether one of the app's screens is showing: a restart would close it under the user's finger. */
    private fun isAppOnScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /** Removes and re-adds the service's entry, which makes the system bind it afresh. Rate-limited so a
     * service that genuinely can't start doesn't get nudged in a loop. */
    @Synchronized
    fun rebindService(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastRebindAtMs != 0L && now - lastRebindAtMs < MIN_REBIND_INTERVAL_MS) return false
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        lastRebindAtMs = now

        val resolver = context.contentResolver
        val self = ComponentName(context, WheelKeyService::class.java).flattenToString()
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val others = current.split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != ComponentName.unflattenFromString(self) }
        AppLogger.log(TAG, "Wheel button service is enabled but not running — nudging the system to bind it")
        return runCatching {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Thread.sleep(REBIND_GAP_MS)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + self).joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.onFailure { AppLogger.logError(TAG, "Rebinding the wheel button service failed", it) }.getOrDefault(false)
    }

    /**
     * Puts the service back in the enabled list if it has dropped out, using the WRITE_SECURE_SETTINGS
     * the app already holds — no ADB, no waiting, safe to call from anywhere (a broadcast receiver, app
     * start-up). Android removes an app's accessibility services from that list whenever the app is
     * force-stopped, and the head unit's cleaner force-stops apps around sleep/wake, which silently
     * turned the wheel button off until the next manual "Turn on".
     *
     * @return true if the service is enabled afterwards.
     */
    fun ensureServiceEnabled(context: Context): Boolean {
        if (isServiceEnabled(context)) return true
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            AppLogger.log(TAG, "Wheel button service is off and WRITE_SECURE_SETTINGS isn't held — it needs the Turn on button")
            return false
        }

        val self = ComponentName(context, WheelKeyService::class.java).flattenToString()
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        // Appended, never replaced: the head unit's own status-bar service and any other app's
        // accessibility service live in the same list.
        val updated = if (current.isBlank()) self else "$current:$self"
        val ok = runCatching {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated) &&
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.onFailure { AppLogger.logError(TAG, "Writing the enabled accessibility services failed", it) }
            .getOrDefault(false)
        if (ok) AppLogger.log(TAG, "Wheel button service had dropped out of the enabled list — switched back on")
        return ok
    }
}

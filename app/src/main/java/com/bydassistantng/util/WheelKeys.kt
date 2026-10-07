package com.bydassistantng.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.KeyEvent
import android.os.SystemClock
import com.bydassistantng.R
import com.bydassistantng.service.WheelKeyService

/**
 * The steering-wheel mic button isn't a standard Android key. On this BYD DiLink unit it arrives
 * from the `simulate-keys` input device (kernel scan codes 290 / 312) and the unit's key layout
 * maps those to BYD-private keycodes, which no `KeyEvent.KEYCODE_*` constant covers. Captured with
 * `getevent` / logcat: a short press is 320, a long press is 328.
 */
private const val TAG = "WheelKeys"
private const val SELF_BIND_GRACE_MS = 3_000L
private const val REBIND_GAP_MS = 1_000L
private const val MIN_REBIND_INTERVAL_MS = 30_000L

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
     * Blocks for a few seconds — call it from a background thread.
     *
     * @return true if the service is running afterwards.
     */
    @Synchronized
    fun ensureServiceRunning(context: Context): Boolean {
        if (!ensureServiceEnabled(context)) return false // missing from the list: adding it makes the system bind it
        if (WheelKeyService.instance != null) return true
        // When our process was started *by* the system binding the service, it is about to connect on its own.
        Thread.sleep(SELF_BIND_GRACE_MS)
        if (WheelKeyService.instance != null) return true
        return rebindService(context)
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

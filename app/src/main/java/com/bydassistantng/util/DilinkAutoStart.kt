package com.bydassistantng.util

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * BYD DiLink head units gate background auto-start (boot receivers, services started outside
 * a visible app) behind their own `com.byd.appstartmanagement` whitelist — without being allowed
 * there, the steering-wheel button may not work after a reboot until someone opens the app by hand. Ported from the previous project; on this unit the intent resolves to
 * `com.byd.appstartmanagement/.frame.AppStartManagement`.
 *
 * There is no API to *read* whether an app is whitelisted, which is why callers can only track
 * "the user has opened this screen since the last app update" (see PreferencesRepository).
 */
object DilinkAutoStart {
    fun isDilink(): Boolean =
        listOf(Build.BRAND, Build.FINGERPRINT, Build.MODEL, Build.PRODUCT).any { it.contains("dilink", ignoreCase = true) }

    /** @return false if the screen couldn't be opened (non-DiLink firmware, or it's been renamed). */
    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent("android.intent.action.BYD_APPSTARTMANAGEMENT").apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }.isSuccess
}

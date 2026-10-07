package com.bydassistantng.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.util.AppLogger
import com.bydassistantng.util.WheelKeys

private const val TAG = "BootReceiver"

/**
 * Brings the steering-wheel button back after the head unit boots, wakes, or the app is updated.
 *
 * On this head unit "wake" is a quick-boot: the system re-sends boot broadcasts without a real reboot,
 * and BYD's cleaner force-stops apps around it. A force-stop makes Android drop the app's accessibility
 * service from the enabled list, so unless something puts it back the button is dead until the user
 * opens the app and presses "Turn on" (what happened after the first overnight wake). Apps that map
 * the steering-wheel buttons listen for `QUICKBOOT_POWERON` for the same reason.
 *
 * Only delivered if BYD's auto-start manager lets this app start in the background — hence the
 * "Auto-start after reboot" entry in Settings.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.log(TAG, "Received ${intent.action}")
        // Off the main thread (and kept alive past onReceive) because it may wait a few seconds to see
        // whether the system binds the service by itself before nudging it.
        val pending = goAsync()
        Thread {
            try {
                WheelKeys.ensureServiceRunning(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

package com.bydassistantng.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.greeting.GreetingCoordinator
import com.bydassistantng.util.AppLogger
import com.bydassistantng.util.AppUser
import com.bydassistantng.util.WheelKeys
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val TAG = "BootReceiver"

/** How the receiver reaches the app's singletons (it is not created by Hilt). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootEntryPoint {
    fun greeting(): GreetingCoordinator
}

/**
 * Brings the steering-wheel button back after the head unit boots, wakes, or the app is updated, and tells the greeting
 * that the car has woken.
 *
 * On this head unit "wake" is a quick-boot: the system re-sends boot broadcasts without a real reboot,
 * and BYD's cleaner force-stops apps around it. A force-stop makes Android drop the app's accessibility
 * service from the enabled list, so unless something puts it back the button is dead until the user
 * opens the app and presses "Turn on" (what happened after the first overnight wake). Apps that map
 * the steering-wheel buttons listen for `QUICKBOOT_POWERON` for the same reason.
 *
 * Only delivered if BYD's auto-start manager lets this app start in the background — hence the
 * "Auto-start after reboot" entry in Settings.
 *
 * The apps are stopped when the car is switched off and started again by this broadcast, which is the one sign the app
 * gets that the car has woken. An app update (`MY_PACKAGE_REPLACED`) is not a wake.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The car's second user gets the boot broadcast too, for a copy of the app that must stay idle (see [AppUser]).
        if (!AppUser.isMain()) {
            AppLogger.log(TAG, "Received ${intent.action} as another Android user — ignored")
            return
        }
        AppLogger.log(TAG, "Received ${intent.action}")
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { EntryPointAccessors.fromApplication(context.applicationContext, BootEntryPoint::class.java).greeting().onWake() }
                .onFailure { AppLogger.logError(TAG, "Could not tell the greeting about the wake", it) }
        }
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

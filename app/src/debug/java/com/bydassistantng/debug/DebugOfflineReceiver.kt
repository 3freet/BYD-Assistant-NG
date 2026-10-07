package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.util.AppLogger
import com.bydassistantng.util.NetworkDebug

/** `am broadcast -a com.bydassistantng.debug.OFFLINE --ez on true -n <pkg>/com.bydassistantng.debug.DebugOfflineReceiver`
 * — while on, the next voice session fails exactly as it does with the hotspot down (until switched off or the app dies). */
class DebugOfflineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NetworkDebug.simulateOffline = intent.getBooleanExtra("on", false)
        AppLogger.log("DebugOffline", "Simulated offline is now ${NetworkDebug.simulateOffline}")
    }
}

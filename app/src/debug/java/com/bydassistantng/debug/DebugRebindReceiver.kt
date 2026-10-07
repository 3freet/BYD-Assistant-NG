package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.util.WheelKeys

/** `am broadcast -a com.bydassistantng.debug.REBIND -n <pkg>/com.bydassistantng.debug.DebugRebindReceiver` —
 * runs the same nudge the wake path uses, so it can be checked without putting the car to sleep. */
class DebugRebindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                WheelKeys.rebindService(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

package com.bydassistantng.audio

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Remembers, across a death of the app, that the exterior speaker's route was switched on and has not been seen switched off, so that the
 * next run can put it back (see [ExteriorSpeaker.recoverLeftRoute]).
 *
 * It has to be remembered here because the helper that holds the route cannot do it: it is a process on the end of the app's ADB connection,
 * and when the app dies (a crash, the system ending it, an update being installed) the connection goes and the system ends the helper with it,
 * without letting it switch anything off. The guard inside the helper (no heartbeat for a few seconds, a time limit) only works while the helper
 * lives.
 *
 * Written with `commit = true`, not the asynchronous default: the app may be gone the next moment, and what is written then must already
 * be on disk.
 */
internal class ExteriorRouteMemory(private val prefs: SharedPreferences) {
    /** A route was switched on and the switch-off has not been seen to be taken since. */
    val possiblyOn: Boolean get() = prefs.getBoolean(KEY, false)

    /** Before a route is asked for: from here until [clear] it may be on. */
    fun markOn() {
        prefs.edit(commit = true) { putBoolean(KEY, true) }
    }

    /** The car has taken the switch-off (or the route was never on). */
    fun clear() {
        if (possiblyOn) prefs.edit(commit = true) { putBoolean(KEY, false) }
    }

    private companion object {
        const val KEY = "route_possibly_on"
    }
}

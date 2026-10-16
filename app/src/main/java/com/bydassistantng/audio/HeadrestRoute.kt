package com.bydassistantng.audio

import android.content.Context
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.HeadrestSound
import com.bydassistantng.vehicle.ShellHelperVehicleController
import com.bydassistantng.vehicle.VehicleCommand
import com.bydassistantng.vehicle.VehicleCommandRegistry
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.VehicleQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "HeadrestRoute"

/**
 * Makes the assistant's voice come out of the driver's headrest speaker, for as long as a conversation lasts.
 *
 * The car's amplifier decides what the headrest plays, from the headrest sound mode the user picks on its screen
 * (see [HeadrestSound]). In **interactive** mode the navigation stream (which the assistant then speaks on, see
 * [VoiceChannel.NAVI_STREAM]) is heard in the headrest only, while the music stays in the car's speakers (the voice
 * stream is not moved by the mode, tried by ear). So the mode is put on interactive when a conversation opens
 * ([engage]) and put back as it was when it is over ([release]). A mode that is already interactive is left alone, and one the user changed meanwhile
 * is not touched at the end.
 *
 * What it will give back is written down before the mode is changed, so a conversation the app never got to finish
 * (it was stopped, or the head unit slept) is undone the next time anything here runs ([release] or [engage]).
 */
@Singleton
class HeadrestRoute internal constructor(
    private val controller: VehicleController,
    private val store: Store,
    private val setCommand: () -> VehicleCommand?,
    private val log: (String) -> Unit,
) {
    @Inject
    constructor(@ApplicationContext context: Context, controller: ShellHelperVehicleController) : this(
        controller,
        PreferencesStore(context),
        { VehicleCommandRegistry.byId(HeadrestSound.COMMAND_ID) },
        { AppLogger.log(TAG, it) },
    )

    /** Where the mode to give back is kept between runs of the app. */
    interface Store {
        /** The mode to put the headrest back on, while the app has it on interactive for a conversation; null otherwise. */
        var restoreTo: Int?
    }

    // One change at a time: a conversation that opens as the last one lets go must not interleave with it.
    private val mutex = Mutex()

    /** What the car says about its headrest speaker, or null when it could not be asked. */
    suspend fun read(): HeadrestSound.State? =
        // Off the caller's thread, which may be the main one: the car is reached over a socket.
        when (val answer = withContext(Dispatchers.IO) { controller.query(VehicleQuery.HEADREST_SOUND) }) {
            is VehicleDispatchResult.Success -> HeadrestSound.parse(answer.note)
            else -> {
                log("Could not read the headrest sound: $answer")
                null
            }
        }

    /**
     * Puts the headrest on interactive so that the voice is heard there. True when it is (now, or already); false when
     * the car has no headrest speaker, could not be reached, or would not change, in which case the voice is simply
     * heard where it always was.
     */
    suspend fun engage(): Boolean = mutex.withLock {
        val pending = store.restoreTo
        val state = read() ?: return@withLock false
        if (!state.hasSpeaker) {
            log("No headrest speaker on this car (${HeadrestSound.encode(state)})")
            return@withLock false
        }
        if (state.mode == HeadrestSound.MODE_INTERACTIVE) {
            // Ours from a conversation just before (kept for the one that follows), or the user's own choice.
            log("The headrest sound is already interactive" + if (pending != null) " (kept from the last conversation)" else "")
            return@withLock true
        }
        // Whatever an earlier conversation was to give back is moot now: the mode is not the one it set.
        store.restoreTo = state.mode
        if (!set(HeadrestSound.MODE_INTERACTIVE)) {
            store.restoreTo = null
            return@withLock false
        }
        log("The headrest sound is now interactive (it was ${describe(state.mode)}): the voice is heard in the headrest")
        true
    }

    /** Gives the headrest sound back as it was before [engage], unless the user has changed it since. */
    suspend fun release() = mutex.withLock {
        val restoreTo = store.restoreTo ?: return@withLock
        val now = read() ?: return@withLock // not reachable now: it stays written down for the next time
        store.restoreTo = null
        if (now.mode != HeadrestSound.MODE_INTERACTIVE) {
            log("The headrest sound is ${describe(now.mode)}, not interactive: left as it is")
            return@withLock
        }
        if (set(restoreTo)) log("The headrest sound is back to ${describe(restoreTo)}")
    }

    /** Whether something still has to be given back (a conversation was cut short). */
    val hasPending: Boolean get() = store.restoreTo != null

    private suspend fun set(mode: Int): Boolean {
        val command = setCommand() ?: run {
            log("The headrest sound command is not available")
            return false
        }
        return when (val answer = withContext(Dispatchers.IO) { controller.dispatch(command, mode) }) {
            is VehicleDispatchResult.Success -> true
            else -> {
                log("The car would not set the headrest sound to ${describe(mode)}: $answer")
                false
            }
        }
    }

    private fun describe(mode: Int?): String = when (mode) {
        HeadrestSound.MODE_OFF -> "off"
        HeadrestSound.MODE_SURROUND -> "surround"
        HeadrestSound.MODE_INTERACTIVE -> "interactive"
        HeadrestSound.MODE_EXCLUSIVE -> "exclusive"
        else -> "unknown ($mode)"
    }

    private class PreferencesStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("headrest_route", Context.MODE_PRIVATE)

        override var restoreTo: Int?
            get() = prefs.getInt(KEY, -1).takeIf { it >= 0 }
            set(value) {
                prefs.edit().apply { if (value == null) remove(KEY) else putInt(KEY, value) }.apply()
            }
    }

    private companion object {
        const val KEY = "restore_to"
    }
}

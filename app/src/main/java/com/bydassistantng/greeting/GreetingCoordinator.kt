package com.bydassistantng.greeting

import android.content.Context
import com.bydassistantng.audio.VoiceSpeaker
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.gemini.LiveConversationController
import com.bydassistantng.service.WheelKeyService
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.DriverPresence
import com.bydassistantng.vehicle.OutsideTemperatureTool
import com.bydassistantng.vehicle.ShellHelperVehicleController
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.VehicleQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Greeting"

/** Where the conversation is started from: the part of the app that owns the microphone and the on-screen banner. */
interface GreetingHost {
    /** A conversation is running, or one is being started. */
    val busy: Boolean

    /** Starts a conversation that opens with the assistant saying [opening], heard from [speaker]. */
    suspend fun greet(opening: String, speaker: VoiceSpeaker): GreetResult
}

enum class GreetResult {
    /** The conversation is under way. */
    STARTED,

    /** It could not start now (no connection yet, say), but may in a moment. */
    LATER,

    /** It will not start, and trying again will not help. */
    NO,
}

/**
 * The assistant's hello when the driver gets in. It speaks unprompted, so it is held to three things that must all be
 * true, and never to any other reason (not an unlock, not the ignition):
 *
 *  1. the car has just woken from sleep (the wake broadcast, [onWake]);
 *  2. the driver is in the seat;
 *  3. the driver's door is shut.
 *
 * The app is stopped when the car is switched off and only started again by the wake broadcast, which on the car came
 * well after the driver had sat and shut the door (see `docs/byd-internals/driver-presence.md`). So it cannot wait to
 * see those two happen: it reads the seat and the door from the start and greets as soon as both hold, for a limited
 * time after the wake. That time is long, because the car can wake for a remote unlock or a pre-cooling and then wait for
 * the driver for many minutes; while nobody is about it only looks every few seconds. One greeting per wake, and none if
 * the driver has started a conversation themselves.
 */
@Singleton
class GreetingCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val enabled: suspend () -> Boolean,
    private val car: VehicleController,
    // Where the car is asked: off the main thread (the car is reached over a socket), which the test button runs on.
    private val io: CoroutineDispatcher,
    private val host: () -> GreetingHost?,
    private val languageToGreetIn: suspend () -> String?,
    private val notesInUse: suspend () -> Boolean,
    // Opens the assistant's server session ahead of the greeting, and lets it go if the greeting does not happen.
    private val prepareSession: suspend () -> Unit,
    private val discardSession: () -> Unit,
    private val speaker: suspend () -> VoiceSpeaker,
    private val now: () -> Long,
    private val timeOfDay: () -> GreetingPrompt.TimeOfDay,
    private val sleep: suspend (Long) -> Unit,
    private val log: (String) -> Unit,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        preferences: PreferencesRepository,
        car: ShellHelperVehicleController,
        conversation: LiveConversationController,
    ) : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        enabled = { preferences.greetingEnabled.first() },
        car = car,
        io = Dispatchers.IO,
        host = { WheelKeyService.instance },
        notesInUse = { preferences.longTermMemoryEnabled.first() },
        prepareSession = { conversation.prepare() },
        discardSession = { conversation.discardPrepared() },
        speaker = {
            // The headrest only where the car has one; a car not yet known to have one is tried (the conversation says
            // so in the log and the voice then simply comes from the car).
            val wanted = preferences.greetingSpeaker.first()
            if (wanted == VoiceSpeaker.HEADREST && preferences.headrestSpeakerPresent.first() == false) VoiceSpeaker.CAR else wanted
        },
        // With no language fixed in the settings the assistant follows what the driver says, and the driver has said
        // nothing yet: use the language of the app.
        languageToGreetIn = {
            if (preferences.assistantLanguage.first() != AssistantLanguage.AUTO) null
            else if (AppLanguage.isArabic(context)) "Arabic" else "English"
        },
        now = { System.currentTimeMillis() },
        timeOfDay = { Calendar.getInstance().let { GreetingPrompt.TimeOfDay(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) } },
        sleep = { delay(it) },
        log = { AppLogger.log(TAG, it) },
    )

    private val lock = Any()
    private var loggedWaitingForAssistant = false
    private var greeted = false
    private var sessionAhead = false
    private var activeWake = Long.MIN_VALUE
    private var watching: Job? = null
    private var lastWakeAt = Long.MIN_VALUE / 2

    /** The car has woken (the boot broadcast). Starts watching for the driver; one watch per wake. */
    fun onWake() {
        val t = now()
        synchronized(lock) {
            // The same wake can be announced by more than one broadcast, a moment apart.
            if (t - lastWakeAt < SAME_WAKE_MS) {
                log("Another wake broadcast for the same wake — ignored")
                return
            }
            lastWakeAt = t
            activeWake = t
            watching?.cancel()
            watching = scope.launch {
                try {
                    watch(t)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing here is worth taking the app down: it is an extra the driver did not ask for.
                    log("The greeting stopped on an error: $e")
                }
            }
        }
    }

    private suspend fun watch(wakeAt: Long) {
        loggedWaitingForAssistant = false
        greeted = false
        sessionAhead = false
        if (!enabled()) {
            log("The greeting is off — not watching")
            return
        }
        log("The car woke: waiting for the driver to sit down and shut the door (up to ${WINDOW_MS / 60_000} min)")
        try {
            watchSeatAndDoor(wakeAt)
        } finally {
            // Not when a newer wake has taken over: the session now open is that one's.
            if (sessionAhead && !greeted && activeWake == wakeAt) discardSession()
        }
    }

    /**
     * While the driver gets in, the assistant's session is opened, so that the greeting has only to be said. It is done
     * when somebody is at the door, not at the wake: a driver may be many minutes away, and a session kept open that
     * long is worth nothing. If it is not wanted after all (the window runs out, a conversation was started by hand,
     * the door is shut again with nobody in) it is let go.
     */
    private fun openSessionAhead() {
        if (sessionAhead) return
        sessionAhead = true
        scope.launch {
            try {
                prepareSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Could not open the session ahead: $e")
            }
        }
    }

    private fun letSessionGo() {
        if (!sessionAhead) return
        sessionAhead = false
        discardSession()
    }

    private suspend fun watchSeatAndDoor(wakeAt: Long) {
        var steady = 0
        var triedSince: Long? = null
        var lastShown: DriverPresence.State? = null
        var unreadable = false
        var firstRead = true
        while (now() - wakeAt < WINDOW_MS) {
            val state = readPresence()
            if (state == null) {
                if (!unreadable) log("Could not read the seat and the door yet")
                unreadable = true
                steady = 0
            } else {
                unreadable = false
                if (state != lastShown) {
                    log("Driver's seat ${describeSeat(state.seat)}, door ${describeDoor(state.door)}")
                    lastShown = state
                }
                // Already seated with the door shut when first looked at: that is no flicker, it was so before the
                // app started (the wake broadcast comes late), so there is nothing to wait to confirm.
                steady = if (state.ready) (if (firstRead) STEADY_READS else steady + 1) else 0
                if (state.someoneAbout) openSessionAhead() else if (state.nobodyAbout) letSessionGo()
            }
            firstRead = false
            if (steady < STEADY_READS) {
                triedSince = null
            } else {
                val since = triedSince ?: now().also { triedSince = it }
                val outcome = attempt()
                if (outcome == Attempt.DONE) return
                if (now() - since >= PATIENCE_MS) {
                    log("Gave up greeting: it could not start within ${PATIENCE_MS / 1000} s of the driver being ready")
                    return
                }
                if (outcome == Attempt.WAIT_FOR_ASSISTANT) {
                    sleep(HOST_POLL_MS)
                    continue
                }
                sleep(RETRY_MS)
            }
            // Nobody about: the door has to open before anyone can sit, and it stays open for seconds, so a slow look is enough.
            sleep(if (state?.nobodyAbout == true) IDLE_POLL_MS else POLL_MS)
        }
        log("No greeting: the driver was not in the seat with the door shut within ${WINDOW_MS / 60_000} min of the wake")
    }

    private enum class Attempt {
        DONE,

        /** The conversation could not start (no connection yet, say): try again in a few seconds, a few times. */
        TRY_AGAIN,

        /** The service that owns the microphone has not bound yet (a few seconds after the wake): look again very soon. */
        WAIT_FOR_ASSISTANT,
    }

    private suspend fun attempt(): Attempt {
        val host = host() ?: run {
            // The assistant's service binds a few seconds after the wake.
            if (!loggedWaitingForAssistant) log("The assistant is not running yet — waiting")
            loggedWaitingForAssistant = true
            return Attempt.WAIT_FOR_ASSISTANT
        }
        if (host.busy) {
            log("A conversation is already under way — no greeting")
            return Attempt.DONE
        }
        return when (host.greet(opening(), speaker())) {
            GreetResult.STARTED -> {
                log("Greeting the driver")
                greeted = true
                Attempt.DONE
            }
            GreetResult.LATER -> {
                log("The greeting could not start yet — trying again")
                Attempt.TRY_AGAIN
            }
            GreetResult.NO -> {
                log("Not greeting")
                Attempt.DONE
            }
        }
    }

    private suspend fun opening(): String {
        val time = timeOfDay()
        return GreetingPrompt.build(GreetingPrompt.partOfDay(time.hour), time, readOutsideTemperature(), languageToGreetIn(), notesInUse())
    }

    /**
     * Plays the greeting now, as if the car had just woken and the driver had sat down and shut the door: the Settings
     * "test" button. Nothing is waited for or read from the seat and the door; everything else is the real thing.
     */
    suspend fun simulate(): SimulationOutcome {
        val host = host() ?: return SimulationOutcome.NOT_RUNNING
        if (host.busy) return SimulationOutcome.BUSY
        log("Test: playing the greeting now")
        return try {
            if (host.greet(opening(), speaker()) == GreetResult.STARTED) SimulationOutcome.STARTED else SimulationOutcome.FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("The test greeting stopped on an error: $e")
            SimulationOutcome.FAILED
        }
    }

    enum class SimulationOutcome {
        STARTED,

        /** A conversation is already going on. */
        BUSY,

        /** The service that owns the microphone is not running, so nothing can speak. */
        NOT_RUNNING,

        /** It could not start (no connection, no key, no microphone permission). */
        FAILED,
    }

    /** The seat and the door, or null when they could not be read. Never throws: the greeting is not worth a crash. */
    private suspend fun readPresence(): DriverPresence.State? = ask(VehicleQuery.DRIVER_PRESENCE)?.let { DriverPresence.parse(it) }

    private suspend fun readOutsideTemperature(): Int? {
        val note = ask(VehicleQuery.OUTSIDE_TEMPERATURE) ?: return null
        val reading = OutsideTemperatureTool.parse(note) ?: return null
        // Only a Celsius reading is trusted (see [OutsideTemperatureTool]).
        return reading.celsius.takeIf { reading.unitCode == CELSIUS_UNIT_CODE }
    }

    private suspend fun ask(query: VehicleQuery): String? = try {
        (withContext(io) { car.query(query) } as? VehicleDispatchResult.Success)?.note
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Could not read ${query.id}: $e")
        null
    }

    private fun describeSeat(seat: Int?) = when (seat) {
        DriverPresence.SEAT_OCCUPIED -> "occupied"
        DriverPresence.SEAT_EMPTY -> "empty"
        else -> "unread"
    }

    private fun describeDoor(door: Int?) = when (door) {
        DriverPresence.DOOR_SHUT -> "shut"
        DriverPresence.DOOR_OPEN -> "open"
        else -> "unread"
    }

    internal companion object {
        /**
         * How long after the wake the driver can still be greeted; a later arrival is not "the car just woke". The car
         * wakes for a remote unlock or a pre-cooling and can wait ten minutes or more for the driver.
         */
        const val WINDOW_MS = 30 * 60_000L
        const val POLL_MS = 400L

        /** How often to look while nobody is about (the seat empty, the door shut). */
        const val IDLE_POLL_MS = 2_000L

        /** How often to look for the assistant's service to have bound, when the driver is ready and it has not. */
        const val HOST_POLL_MS = 250L

        /** The seated-and-shut state has to be read this many times running, so a flicker is not a greeting. */
        const val STEADY_READS = 2

        /**
         * How long a greeting that cannot start yet (no connection to the server, the assistant's service not bound) is
         * kept trying, from the driver being ready.
         */
        const val PATIENCE_MS = 90_000L
        const val RETRY_MS = 4_000L
        const val SAME_WAKE_MS = 20_000L
        private const val CELSIUS_UNIT_CODE = 1
    }
}

package com.bydassistantng.greeting

import com.bydassistantng.audio.VoiceSpeaker
import com.bydassistantng.vehicle.DriverPresence
import com.bydassistantng.vehicle.OutsideTemperatureTool
import com.bydassistantng.vehicle.RawOutsideTemperature
import com.bydassistantng.vehicle.VehicleCommand
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.VehicleQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingCoordinatorTest {
    private val seatedShut = DriverPresence.State(DriverPresence.SEAT_OCCUPIED, DriverPresence.DOOR_SHUT)
    private val seatedOpen = DriverPresence.State(DriverPresence.SEAT_OCCUPIED, DriverPresence.DOOR_OPEN)
    private val emptyOpen = DriverPresence.State(DriverPresence.SEAT_EMPTY, DriverPresence.DOOR_OPEN)
    private val emptyShut = DriverPresence.State(DriverPresence.SEAT_EMPTY, DriverPresence.DOOR_SHUT)

    /** A car that answers the seat and door readings in turn (the last one for ever), and a fixed outside temperature. */
    private class FakeCar(private val states: List<DriverPresence.State?>, private val celsius: Int? = 24) : VehicleController {
        var presenceReads = 0
        var temperatureReads = 0

        /** The car link blows up instead of answering (a closed socket, say). */
        var throws = false

        override suspend fun dispatch(command: VehicleCommand, value: Int): VehicleDispatchResult =
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, "not used")

        override suspend fun query(query: VehicleQuery): VehicleDispatchResult = if (throws) throw java.io.IOException("Socket closed") else when (query) {
            VehicleQuery.DRIVER_PRESENCE -> {
                val state = states[minOf(presenceReads, states.size - 1)]
                presenceReads++
                if (state == null) VehicleDispatchResult.Failure(VehicleDispatchError.HELPER_UNAVAILABLE, "off")
                else VehicleDispatchResult.Success(DriverPresence.encode(state))
            }
            VehicleQuery.OUTSIDE_TEMPERATURE -> {
                temperatureReads++
                if (celsius == null) VehicleDispatchResult.Failure(VehicleDispatchError.HELPER_UNAVAILABLE, "off")
                else VehicleDispatchResult.Success(OutsideTemperatureTool.encode(RawOutsideTemperature(celsius, unitCode = 1)))
            }
            else -> error("unexpected $query")
        }
    }

    private class FakeHost(private val results: MutableList<GreetResult> = mutableListOf(GreetResult.STARTED)) : GreetingHost {
        override var busy = false
        val openings = mutableListOf<String>()
        val speakers = mutableListOf<VoiceSpeaker>()

        override suspend fun greet(opening: String, speaker: VoiceSpeaker): GreetResult {
            openings += opening
            speakers += speaker
            return if (results.size > 1) results.removeAt(0) else results.first()
        }
    }

    /** A coordinator whose clock only moves when it sleeps, and that runs a watch to its end inside [GreetingCoordinator.onWake]. */
    private inner class Rig(
        val car: FakeCar,
        val host: FakeHost? = FakeHost(),
        var enabled: Boolean = true,
        var hostFromTheStart: Boolean = true,
        language: String? = null,
        notes: Boolean = false,
        speaker: VoiceSpeaker = VoiceSpeaker.HEADREST,
        prepareThrows: Boolean = false,
    ) {
        var clock = 1_000_000L
        val logs = mutableListOf<String>()
        var prepares = 0
        var discards = 0
        private var hostReads = 0
        val coordinator = GreetingCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            enabled = { enabled },
            car = car,
            io = Dispatchers.Unconfined,
            host = { if (hostFromTheStart || hostReads++ > 0) host else null },
            languageToGreetIn = { language },
            notesInUse = { notes },
            prepareSession = { prepares++; if (prepareThrows) throw java.io.IOException("no network") },
            discardSession = { discards++ },
            speaker = { speaker },
            now = { clock },
            timeOfDay = { GreetingPrompt.TimeOfDay(8, 5) },
            sleep = { clock += it },
            log = { logs += it },
        )
    }

    @Test
    fun greetsOnceTheDriverIsSeatedAndTheDoorIsShut() {
        val rig = Rig(FakeCar(listOf(emptyShut, emptyOpen, seatedOpen, seatedShut, seatedShut)))
        rig.coordinator.onWake()

        assertEquals(1, rig.host!!.openings.size)
        val opening = rig.host.openings.single()
        assertTrue(opening, opening.contains("24 degrees Celsius"))
        assertTrue(opening, opening.contains("good morning"))
        assertTrue(opening, opening.contains("08:05"))
    }

    @Test
    fun opensTheSessionAheadWhileWaitingForTheDriver() {
        val rig = Rig(FakeCar(listOf(emptyOpen, seatedOpen, seatedShut, seatedShut)))
        rig.coordinator.onWake()

        assertEquals(1, rig.prepares)
        // It is used by the greeting, not thrown away.
        assertEquals(0, rig.discards)
        assertEquals(1, rig.host!!.openings.size)
    }

    @Test
    fun letsTheSessionGoWhenTheDriverNeverComes() {
        val rig = Rig(FakeCar(listOf(emptyOpen)))
        rig.coordinator.onWake()

        assertEquals(1, rig.prepares)
        assertEquals(1, rig.discards)
    }

    @Test
    fun letsTheSessionGoWhenAConversationIsAlreadyUnderWay() {
        val host = FakeHost().apply { busy = true }
        val rig = Rig(FakeCar(listOf(seatedShut)), host)
        rig.coordinator.onWake()

        assertEquals(1, rig.discards)
    }

    @Test
    fun opensNoSessionWhenTheGreetingIsOff() {
        val rig = Rig(FakeCar(listOf(seatedShut)), enabled = false)
        rig.coordinator.onWake()

        assertEquals(0, rig.prepares)
        assertEquals(0, rig.discards)
    }

    @Test
    fun aFailureToOpenTheSessionAheadDoesNotStopTheGreeting() {
        val rig = Rig(FakeCar(listOf(seatedShut)), prepareThrows = true)
        rig.coordinator.onWake()

        assertEquals(1, rig.host!!.openings.size)
        assertTrue(rig.logs.any { it.startsWith("Could not open the session ahead") })
    }

    @Test
    fun greetsFromTheSpeakerTheGreetingIsSetTo() {
        val headrest = Rig(FakeCar(listOf(seatedShut)), speaker = VoiceSpeaker.HEADREST)
        headrest.coordinator.onWake()
        assertEquals(listOf(VoiceSpeaker.HEADREST), headrest.host!!.speakers)

        val car = Rig(FakeCar(listOf(seatedShut)), speaker = VoiceSpeaker.CAR)
        car.coordinator.onWake()
        assertEquals(listOf(VoiceSpeaker.CAR), car.host!!.speakers)
    }

    @Test
    fun theTestUsesTheGreetingSpeakerToo() = runBlocking {
        val rig = Rig(FakeCar(listOf(emptyOpen)), speaker = VoiceSpeaker.CAR)
        rig.coordinator.simulate()

        assertEquals(listOf(VoiceSpeaker.CAR), rig.host!!.speakers)
    }

    @Test
    fun doesNotGreetWhileTheDoorIsStillOpen() {
        val rig = Rig(FakeCar(listOf(emptyOpen, seatedOpen)))
        rig.coordinator.onWake()

        assertTrue(rig.host!!.openings.isEmpty())
        assertTrue(rig.logs.last(), rig.logs.last().startsWith("No greeting"))
    }

    @Test
    fun doesNotGreetWhenTheDoorIsShutWithNobodyInTheSeat() {
        val rig = Rig(FakeCar(listOf(emptyShut)))
        rig.coordinator.onWake()

        assertTrue(rig.host!!.openings.isEmpty())
    }

    @Test
    fun aFlickerOfSeatedAndShutIsNotEnough() {
        // Ready once, then not, then ready for good: the greeting waits for the steady state.
        val rig = Rig(FakeCar(listOf(seatedOpen, seatedShut, seatedOpen, seatedShut, seatedShut)))
        rig.coordinator.onWake()

        assertEquals(1, rig.host!!.openings.size)
        assertEquals(5, rig.car.presenceReads)
    }

    @Test
    fun greetsAtOnceWhenTheDriverIsAlreadySeatedWithTheDoorShutAtTheFirstLook() {
        // The wake broadcast comes late: the driver is often in the seat before the app can look. No second look needed.
        val rig = Rig(FakeCar(listOf(seatedShut)))
        rig.coordinator.onWake()

        assertEquals(1, rig.host!!.openings.size)
        assertEquals(1, rig.car.presenceReads)
    }

    @Test
    fun staysQuietWhenTheGreetingIsOff() {
        val rig = Rig(FakeCar(listOf(seatedShut)), enabled = false)
        rig.coordinator.onWake()

        assertTrue(rig.host!!.openings.isEmpty())
        assertEquals(0, rig.car.presenceReads)
    }

    @Test
    fun staysQuietWhenAConversationIsAlreadyUnderWay() {
        val host = FakeHost().apply { busy = true }
        val rig = Rig(FakeCar(listOf(seatedShut)), host)
        rig.coordinator.onWake()

        assertTrue(host.openings.isEmpty())
    }

    @Test
    fun givesUpWhenTheWindowIsOver() {
        val rig = Rig(FakeCar(listOf(emptyOpen)))
        rig.coordinator.onWake()

        // A read every POLL_MS for the whole window, and no more.
        val reads = rig.car.presenceReads
        val expected = (GreetingCoordinator.WINDOW_MS / GreetingCoordinator.POLL_MS).toInt()
        assertTrue("$reads reads, expected about $expected", reads in expected - 5..expected + 5)
    }

    @Test
    fun keepsTryingWhenTheConversationCannotStartYet() {
        val host = FakeHost(mutableListOf(GreetResult.LATER, GreetResult.LATER, GreetResult.STARTED))
        val rig = Rig(FakeCar(listOf(seatedShut)), host)
        rig.coordinator.onWake()

        assertEquals(3, host.openings.size)
    }

    @Test
    fun givesUpAfterTooManyTriesThatCouldNotStart() {
        val host = FakeHost(mutableListOf(GreetResult.LATER))
        val rig = Rig(FakeCar(listOf(seatedShut)), host)
        rig.coordinator.onWake()

        assertEquals(GreetingCoordinator.MAX_ATTEMPTS, host.openings.size)
    }

    @Test
    fun doesNotTryAgainWhenItWillNeverStart() {
        val host = FakeHost(mutableListOf(GreetResult.NO))
        val rig = Rig(FakeCar(listOf(seatedShut)), host)
        rig.coordinator.onWake()

        assertEquals(1, host.openings.size)
    }

    @Test
    fun waitsForTheAssistantToBeRunning() {
        val rig = Rig(FakeCar(listOf(seatedShut)), hostFromTheStart = false)
        rig.coordinator.onWake()

        assertEquals(1, rig.host!!.openings.size)
        // It looked again after a very short wait, not after the seconds a failed start gets.
        assertTrue("${rig.clock - 1_000_000L} ms", rig.clock - 1_000_000L < GreetingCoordinator.RETRY_MS)
    }

    @Test
    fun greetsWithoutTheTemperatureWhenTheCarGivesNone() {
        val rig = Rig(FakeCar(listOf(seatedShut), celsius = null))
        rig.coordinator.onWake()

        assertTrue(rig.host!!.openings.single().let { !it.contains("Celsius") })
    }

    @Test
    fun namesTheLanguageToGreetInWhenOneIsGiven() {
        val rig = Rig(FakeCar(listOf(seatedShut)), language = "Arabic")
        rig.coordinator.onWake()

        assertTrue(rig.host!!.openings.single().contains("Speak in Arabic."))
    }

    @Test
    fun letsTheAssistantUseTheNotesOnlyWhenLongTermMemoryIsOn() {
        val on = Rig(FakeCar(listOf(seatedShut)), notes = true)
        on.coordinator.onWake()
        assertTrue(on.host!!.openings.single().contains("remembered notes"))

        val off = Rig(FakeCar(listOf(seatedShut)), notes = false)
        off.coordinator.onWake()
        assertFalse(off.host!!.openings.single().contains("remembered note"))
    }

    @Test
    fun theTestPlaysTheGreetingWithoutWaitingForTheSeatOrTheDoor() = runBlocking {
        val rig = Rig(FakeCar(listOf(emptyOpen)))

        assertEquals(GreetingCoordinator.SimulationOutcome.STARTED, rig.coordinator.simulate())
        assertEquals(1, rig.host!!.openings.size)
        // It did not look at the seat or the door at all.
        assertEquals(0, rig.car.presenceReads)
    }

    @Test
    fun theTestWorksWhateverTheSettingIs() = runBlocking {
        val rig = Rig(FakeCar(listOf(emptyOpen)), enabled = false)

        assertEquals(GreetingCoordinator.SimulationOutcome.STARTED, rig.coordinator.simulate())
    }

    @Test
    fun theTestSaysWhenAConversationIsAlreadyUnderWay() = runBlocking {
        val host = FakeHost().apply { busy = true }
        val rig = Rig(FakeCar(listOf(seatedShut)), host)

        assertEquals(GreetingCoordinator.SimulationOutcome.BUSY, rig.coordinator.simulate())
        assertTrue(host.openings.isEmpty())
    }

    @Test
    fun theTestSaysWhenTheServiceIsNotRunning() = runBlocking {
        val rig = Rig(FakeCar(listOf(seatedShut)), host = null)

        assertEquals(GreetingCoordinator.SimulationOutcome.NOT_RUNNING, rig.coordinator.simulate())
    }

    @Test
    fun theTestSaysWhenTheGreetingCouldNotStart() = runBlocking {
        val rig = Rig(FakeCar(listOf(seatedShut)), FakeHost(mutableListOf(GreetResult.LATER)))

        assertEquals(GreetingCoordinator.SimulationOutcome.FAILED, rig.coordinator.simulate())
    }

    @Test
    fun aCarLinkThatBlowsUpDoesNotTakeTheAppDown() {
        val rig = Rig(FakeCar(listOf(seatedShut)).apply { throws = true })
        rig.coordinator.onWake()

        // Nothing could be read, so nothing is said, and nothing is thrown at the caller.
        assertTrue(rig.host!!.openings.isEmpty())
        assertTrue(rig.logs.any { it.startsWith("Could not read driver_presence") })
    }

    @Test
    fun theTestStillGreetsWhenTheTemperatureCannotBeRead() = runBlocking {
        val rig = Rig(FakeCar(listOf(seatedShut)).apply { throws = true })

        assertEquals(GreetingCoordinator.SimulationOutcome.STARTED, rig.coordinator.simulate())
        assertFalse(rig.host!!.openings.single().contains("Celsius"))
    }

    @Test
    fun aSecondBroadcastForTheSameWakeChangesNothing() {
        val rig = Rig(FakeCar(listOf(seatedShut)))
        rig.coordinator.onWake()
        val before = rig.car.presenceReads
        rig.clock = 1_000_000L + 5_000L // a moment after the first wake
        rig.coordinator.onWake()

        assertEquals(before, rig.car.presenceReads)
        assertEquals(1, rig.host!!.openings.size)
    }

    @Test
    fun aLaterWakeIsAWakeOfItsOwn() {
        val rig = Rig(FakeCar(listOf(seatedShut)))
        rig.coordinator.onWake()
        rig.clock += 10 * 60_000L
        rig.coordinator.onWake()

        assertEquals(2, rig.host!!.openings.size)
    }
}

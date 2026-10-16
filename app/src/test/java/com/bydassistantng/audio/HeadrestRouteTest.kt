package com.bydassistantng.audio

import com.bydassistantng.vehicle.HeadrestSound
import com.bydassistantng.vehicle.VehicleCommand
import com.bydassistantng.vehicle.VehicleCommandRegistry
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.VehicleQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadrestRouteTest {
    /** A car whose headrest sound is [mode], that records what it was told to set. */
    private class FakeCar(var mode: Int? = HeadrestSound.MODE_SURROUND, var speakers: Int? = 2) : VehicleController {
        var reachable = true
        var accepts = true
        val sets = mutableListOf<Int>()

        override suspend fun dispatch(command: VehicleCommand, value: Int): VehicleDispatchResult {
            assertEquals(HeadrestSound.COMMAND_ID, command.id)
            if (!accepts) return VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "no")
            sets += value
            mode = value
            return VehicleDispatchResult.Success("confirmed")
        }

        override suspend fun query(query: VehicleQuery): VehicleDispatchResult {
            assertEquals(VehicleQuery.HEADREST_SOUND, query)
            if (!reachable) return VehicleDispatchResult.Failure(VehicleDispatchError.HELPER_UNAVAILABLE, "off")
            return VehicleDispatchResult.Success(HeadrestSound.encode(HeadrestSound.State(mode, speakers)))
        }
    }

    private class MemoryStore(override var restoreTo: Int? = null) : HeadrestRoute.Store

    private val command = VehicleCommandRegistry.byId(HeadrestSound.COMMAND_ID)

    private fun route(car: FakeCar, store: MemoryStore = MemoryStore()) = HeadrestRoute(car, store, { command }, { })

    @Test
    fun theCommandIsInTheRegistry() {
        assertNotNull(command)
    }

    @Test
    fun engagingPutsSurroundOnInteractiveAndReleasingPutsItBack() = runBlocking {
        val car = FakeCar(HeadrestSound.MODE_SURROUND)
        val store = MemoryStore()
        val route = route(car, store)

        assertTrue(route.engage())
        assertEquals(HeadrestSound.MODE_INTERACTIVE, car.mode)
        assertEquals(HeadrestSound.MODE_SURROUND, store.restoreTo)

        route.release()
        assertEquals(HeadrestSound.MODE_SURROUND, car.mode)
        assertNull(store.restoreTo)
        assertEquals(listOf(HeadrestSound.MODE_INTERACTIVE, HeadrestSound.MODE_SURROUND), car.sets)
    }

    @Test
    fun anOffHeadrestIsGivenBackOff() = runBlocking {
        val car = FakeCar(HeadrestSound.MODE_OFF)
        val route = route(car)
        assertTrue(route.engage())
        route.release()
        assertEquals(HeadrestSound.MODE_OFF, car.mode)
    }

    @Test
    fun aHeadrestAlreadyOnInteractiveIsLeftAloneBothWays() = runBlocking {
        val car = FakeCar(HeadrestSound.MODE_INTERACTIVE)
        val store = MemoryStore()
        val route = route(car, store)

        assertTrue(route.engage())
        assertNull(store.restoreTo)
        route.release()
        assertEquals(emptyList<Int>(), car.sets)
        assertEquals(HeadrestSound.MODE_INTERACTIVE, car.mode)
    }

    @Test
    fun aCarWithoutAHeadrestSpeakerIsNotTouched() = runBlocking {
        val car = FakeCar(HeadrestSound.MODE_SURROUND, speakers = 0)
        val store = MemoryStore()
        assertFalse(route(car, store).engage())
        assertEquals(emptyList<Int>(), car.sets)
        assertNull(store.restoreTo)
    }

    @Test
    fun aCarThatCannotBeReachedIsNotTouchedAndNothingIsKept() = runBlocking {
        val car = FakeCar().apply { reachable = false }
        val store = MemoryStore()
        assertFalse(route(car, store).engage())
        assertEquals(emptyList<Int>(), car.sets)
        assertNull(store.restoreTo)
    }

    @Test
    fun aChangeTheCarRefusesLeavesNothingToGiveBack() = runBlocking {
        val car = FakeCar().apply { accepts = false }
        val store = MemoryStore()
        assertFalse(route(car, store).engage())
        assertNull(store.restoreTo)
        assertFalse(route(car, store).hasPending)
    }

    @Test
    fun aModeTheUserChangedMeanwhileIsNotOverwrittenAtTheEnd() = runBlocking {
        val car = FakeCar(HeadrestSound.MODE_SURROUND)
        val store = MemoryStore()
        val route = route(car, store)
        route.engage()

        car.mode = HeadrestSound.MODE_EXCLUSIVE // chosen on the screen during the conversation
        route.release()

        assertEquals(HeadrestSound.MODE_EXCLUSIVE, car.mode)
        assertNull(store.restoreTo)
        assertEquals(listOf(HeadrestSound.MODE_INTERACTIVE), car.sets)
    }

    @Test
    fun whatTheCarCouldNotBeAskedAboutStaysToBeGivenBackNextTime() = runBlocking {
        val car = FakeCar(HeadrestSound.MODE_SURROUND)
        val store = MemoryStore()
        val route = route(car, store)
        route.engage()

        car.reachable = false
        route.release()
        assertEquals(HeadrestSound.MODE_SURROUND, store.restoreTo)
        assertTrue(route.hasPending)

        car.reachable = true
        route.release()
        assertEquals(HeadrestSound.MODE_SURROUND, car.mode)
        assertFalse(route.hasPending)
    }

    @Test
    fun aConversationThatWasCutShortIsUndoneByTheNextRelease() = runBlocking {
        // The app was stopped while the headrest was on interactive: the written-down mode is what comes back.
        val car = FakeCar(HeadrestSound.MODE_INTERACTIVE)
        val store = MemoryStore(restoreTo = HeadrestSound.MODE_EXCLUSIVE)
        route(car, store).release()
        assertEquals(HeadrestSound.MODE_EXCLUSIVE, car.mode)
        assertNull(store.restoreTo)
    }

    @Test
    fun aConversationRightAfterAnotherKeepsTheHeadrestWhereItIs() = runBlocking {
        val car = FakeCar(HeadrestSound.MODE_SURROUND)
        val store = MemoryStore()
        val route = route(car, store)
        route.engage()
        // The next conversation opens before the last one has been given back.
        assertTrue(route.engage())
        assertEquals(listOf(HeadrestSound.MODE_INTERACTIVE), car.sets)
        assertEquals(HeadrestSound.MODE_SURROUND, store.restoreTo)
        route.release()
        assertEquals(HeadrestSound.MODE_SURROUND, car.mode)
    }

    @Test
    fun theHeadrestStateReadsBackWhatItWasWritten() {
        val state = HeadrestSound.State(HeadrestSound.MODE_EXCLUSIVE, 2)
        assertEquals(state, HeadrestSound.parse(HeadrestSound.encode(state)))
        assertEquals(HeadrestSound.State(null, null), HeadrestSound.parse(HeadrestSound.encode(HeadrestSound.State(null, null))))
        assertNull(HeadrestSound.parse("garbage"))
        assertNull(HeadrestSound.parse(null))
        assertTrue(state.hasSpeaker)
        assertFalse(HeadrestSound.State(HeadrestSound.MODE_SURROUND, 0).hasSpeaker)
        assertFalse(HeadrestSound.State(null, 2).hasSpeaker)
    }

    @Test
    fun theHeadrestIsReachedOnTheNavigationStreamAndTheCarOnTheVoiceStream() {
        assertEquals(15, VoiceChannel.streamFor(VoiceSpeaker.HEADREST))
        assertEquals(17, VoiceChannel.streamFor(VoiceSpeaker.CAR))
    }

    @Test
    fun theSpeakerChoiceFallsBackToTheCarSpeakers() {
        assertEquals(VoiceSpeaker.HEADREST, VoiceSpeaker.from("headrest"))
        assertEquals(VoiceSpeaker.CAR, VoiceSpeaker.from("car"))
        assertEquals(VoiceSpeaker.CAR, VoiceSpeaker.from(null))
        assertEquals(VoiceSpeaker.CAR, VoiceSpeaker.from("anything else"))
    }
}

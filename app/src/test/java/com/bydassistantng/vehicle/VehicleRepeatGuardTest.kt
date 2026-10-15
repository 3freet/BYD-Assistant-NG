package com.bydassistantng.vehicle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleRepeatGuardTest {
    private var now = 1_000L
    private val guard = VehicleRepeatGuard(windowMs = 15_000, clock = { now })
    private var sent = 0

    private fun send(id: String, value: Int, result: VehicleDispatchResult = VehicleDispatchResult.Success("HAL returned 0")): VehicleDispatchResult =
        runBlocking {
            guard.dispatch(id, value) {
                sent++
                result
            }
        }

    private fun note(r: VehicleDispatchResult) = (r as VehicleDispatchResult.Success).note

    @Test
    fun theFirstCommandGoesToTheCar() {
        assertEquals("HAL returned 0", note(send("window.passenger", 2)))
        assertEquals(1, sent)
    }

    @Test
    fun theSameCommandAndValueRightAfterIsAnsweredFromHereNotSentAgain() {
        send("window.passenger", 2)
        now += 600
        val again = send("window.passenger", 2)
        assertEquals(CommandAnswers.REPEAT_NOTE, note(again))
        assertEquals(1, sent)
    }

    @Test
    fun aModelThatKeepsCallingStaysAnswered_evenPastTheWindowFromTheFirst() {
        send("window.passenger", 2)
        repeat(40) {
            now += 600 // 24 s in all: longer than the window, but each repeat is within it of the one before
            assertEquals(CommandAnswers.REPEAT_NOTE, note(send("window.passenger", 2)))
        }
        assertEquals(1, sent)
    }

    @Test
    fun aDifferentValueOrCommandIsNotARepeat() {
        send("window.passenger", 2)
        send("window.passenger", 1)
        send("window.driver", 1)
        assertEquals(3, sent)
    }

    @Test
    fun openCloseOpenAllGoThrough() {
        send("window.passenger", 1)
        send("window.passenger", 2)
        send("window.passenger", 1)
        assertEquals(3, sent)
    }

    @Test
    fun afterTheWindowItIsASeparateRequest() {
        send("seat.driver.heating", 3)
        now += 16_000
        send("seat.driver.heating", 3)
        assertEquals(2, sent)
    }

    @Test
    fun aNewUserTurnIsNeverARepeat() {
        send("seat.driver.heating", 3)
        now += 2_000
        guard.reset()
        send("seat.driver.heating", 3)
        assertEquals(2, sent)
    }

    @Test
    fun aFailedCommandIsRetriedNotRemembered() {
        val failure = VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "HAL returned error code -1")
        send("ac.fan", 4, failure)
        now += 600
        send("ac.fan", 4, failure)
        assertEquals(2, sent)
        // …and a success after a failure is sent, then a repeat of it is held.
        send("ac.fan", 4)
        assertEquals(3, sent)
        assertEquals(CommandAnswers.REPEAT_NOTE, note(send("ac.fan", 4)))
    }

    @Test
    fun aBlockedCommandIsNotRemembered() {
        send("trunk", 1, VehicleDispatchResult.Blocked("no"))
        send("trunk", 1, VehicleDispatchResult.Blocked("no"))
        assertEquals(2, sent)
    }

    @Test
    fun aClockThatWentBackwardsIsNotARepeat() {
        send("ac.fan", 4)
        now -= 5_000
        send("ac.fan", 4)
        assertEquals(2, sent)
    }

    @Test
    fun theRepeatAnswerTellsTheModelToStop() {
        assertTrue(CommandAnswers.REPEAT_NOTE.contains("Do not send it again"))
    }
}

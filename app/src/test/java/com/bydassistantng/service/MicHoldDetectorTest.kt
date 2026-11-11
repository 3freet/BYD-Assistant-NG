package com.bydassistantng.service

import com.bydassistantng.service.MicHoldDetector.Outcome
import com.bydassistantng.util.WheelKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wheel's microphone button: a short press against a hold, whichever way the unit sends its two key codes. */
class MicHoldDetectorTest {
    private val short = WheelKeys.MIC_SHORT_PRESS
    private val long = WheelKeys.MIC_LONG_PRESS
    private val detector = MicHoldDetector(holdMs = 1_000)

    private fun down(code: Int, at: Long, repeat: Int = 0) = detector.onKey(code, down = true, repeatCount = repeat, nowMs = at)
    private fun up(code: Int, at: Long) = detector.onKey(code, down = false, repeatCount = 0, nowMs = at)

    @Test
    fun aTapIsAShortPressWhenTheButtonComesUp() {
        assertEquals(Outcome.NONE, down(short, 0))
        assertEquals(Outcome.PRESS, up(short, 120))
        assertFalse(detector.inProgress)
    }

    @Test
    fun heldForTheFullTimeIsAHoldReportedOnTheTimerAndNotAShortPressAfterwards() {
        assertEquals(Outcome.NONE, down(short, 0))
        assertEquals(Outcome.HOLD, detector.onTimer(1_000))
        assertEquals(Outcome.NONE, detector.onTimer(1_050)) // reported once
        assertEquals(Outcome.NONE, up(short, 1_700))
        assertFalse(detector.inProgress)
    }

    @Test
    fun theTimerBeforeTheFullTimeReportsNothing() {
        down(short, 0)
        assertEquals(Outcome.NONE, detector.onTimer(999))
        assertEquals(Outcome.PRESS, up(short, 999)) // and releasing just before the time is still a short press
    }

    @Test
    fun aLongPressCodeWithNoPressSeenGoingDownIsAHoldAtOnce() {
        assertEquals(Outcome.HOLD, down(long, 0))
        assertEquals(Outcome.NONE, up(long, 400))
        assertFalse(detector.inProgress)
        // And the button works as before afterwards.
        down(short, 5_000)
        assertEquals(Outcome.PRESS, up(short, 5_100))
    }

    @Test
    fun aLongPressCodeThatComesEarlyStillWaitsForTheFullTime() {
        assertEquals(Outcome.NONE, down(short, 0))
        assertEquals(Outcome.NONE, down(long, 500)) // the unit calls it long at half a second: not yet a hold
        assertEquals(Outcome.HOLD, detector.onTimer(1_000))
        assertEquals(Outcome.NONE, up(long, 1_400))
        assertEquals(Outcome.NONE, up(short, 1_410))
    }

    @Test
    fun aPressTheUnitCalledLongButWasReleasedBeforeTheFullTimeIsAShortPress() {
        down(short, 0)
        down(long, 500)
        assertEquals(Outcome.NONE, up(long, 700))
        assertEquals(Outcome.PRESS, up(short, 710))
    }

    @Test
    fun aLongPressCodeAfterTheHoldWasReportedChangesNothing() {
        down(short, 0)
        assertEquals(Outcome.HOLD, detector.onTimer(1_000))
        assertEquals(Outcome.NONE, down(long, 1_200))
        assertEquals(Outcome.NONE, up(short, 1_900))
    }

    @Test
    fun autoRepeatsAreIgnored() {
        down(short, 0)
        assertEquals(Outcome.NONE, down(short, 600, repeat = 1))
        assertEquals(Outcome.NONE, down(short, 650, repeat = 2))
        assertEquals(Outcome.HOLD, detector.onTimer(1_000))
    }

    @Test
    fun anotherKeyIsNotItsBusiness() {
        assertEquals(Outcome.NONE, detector.onKey(24, down = true, repeatCount = 0, nowMs = 0))
        assertEquals(Outcome.NONE, detector.onKey(24, down = false, repeatCount = 0, nowMs = 50))
        assertFalse(detector.inProgress)
    }

    @Test
    fun aPressWhoseEndWasNeverSeenIsForgottenAfterAWhile() {
        down(short, 0)
        detector.expireStale(5_000)
        assertTrue(detector.inProgress) // not yet
        detector.expireStale(16_000)
        assertFalse(detector.inProgress)
        down(short, 20_000)
        assertEquals(Outcome.PRESS, up(short, 20_100))
    }

    @Test
    fun twoTapsInARowAreTwoShortPresses() {
        down(short, 0)
        assertEquals(Outcome.PRESS, up(short, 100))
        down(short, 400)
        assertEquals(Outcome.PRESS, up(short, 500))
    }

    @Test
    fun aReleaseWithNoPressSeenIsNothing() {
        assertEquals(Outcome.NONE, up(short, 0))
        assertFalse(detector.inProgress)
    }

    @Test
    fun theHoldTimeIsTheOneSecondOfTheSetting() {
        assertEquals(1_000L, MicHoldDetector.HOLD_MS)
    }
}

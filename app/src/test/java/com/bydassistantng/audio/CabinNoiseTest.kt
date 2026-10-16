package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class CabinNoiseTest {
    @Test
    fun fullScaleIsZeroDecibelsAndHalfScaleIsAboutMinusSix() {
        assertEquals(0.0, CabinNoise.dbfs(ShortArray(100) { Short.MAX_VALUE }), 0.01)
        assertEquals(-6.02, CabinNoise.dbfs(ShortArray(100) { (Short.MAX_VALUE / 2).toShort() }), 0.05)
    }

    @Test
    fun silenceReadsAsTheFloorNotMinusInfinity() {
        assertEquals(CabinNoise.SILENCE_DBFS, CabinNoise.dbfs(ShortArray(100)), 0.0)
        assertEquals(CabinNoise.SILENCE_DBFS, CabinNoise.dbfs(ShortArray(0)), 0.0)
    }

    @Test
    fun theMedianIgnoresASingleLoudWindow() {
        assertEquals(-50.0, CabinNoise.median(listOf(-52.0, -50.0, -10.0, -51.0, -49.0)), 0.0)
        assertEquals(-50.5, CabinNoise.median(listOf(-52.0, -49.0, -51.0, -50.0)), 0.0)
    }

    @Test
    fun aPercentileTakesTheValueAtOrJustBelowThePoint() {
        val values = listOf(-30.0, -60.0, -50.0, -40.0, -55.0)
        assertEquals(-60.0, CabinNoise.percentile(values, 0.0), 0.0)
        assertEquals(-55.0, CabinNoise.percentile(values, 0.25), 0.0)
        assertEquals(-30.0, CabinNoise.percentile(values, 1.0), 0.0)
    }

    @Test
    fun theEstimateIsTheQuietQuarterSoSpeechDoesNotRaiseIt() {
        val windows = NoiseWindows()
        // A cabin at about -58 with the user talking over half of it.
        repeat(30) { windows.add(it * 50L, -58.0 + (it % 3)) }
        repeat(30) { windows.add(1_500L + it * 50L, -30.0) }
        val estimate = windows.estimate(3_000L)!!
        assertEquals(-58.0, estimate, 2.5)
    }

    @Test
    fun thereIsNoEstimateWithTooFewWindowsOrStaleOnes() {
        val windows = NoiseWindows()
        assertEquals(null, windows.estimate(0L))
        repeat(9) { windows.add(it * 50L, -50.0) }
        assertEquals(null, windows.estimate(500L))
        windows.add(450L, -50.0)
        assertEquals(-50.0, windows.estimate(500L)!!, 0.0)
        assertEquals(null, windows.estimate(450L + 20_001L))
    }

    @Test
    fun onlyTheLastThreeSecondsCount() {
        val windows = NoiseWindows()
        repeat(60) { windows.add(it * 50L, -30.0) }
        repeat(60) { windows.add(3_000L + it * 50L, -60.0) }
        assertEquals(-60.0, windows.estimate(6_000L)!!, 0.0)
    }
}

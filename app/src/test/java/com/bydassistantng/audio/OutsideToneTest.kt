package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OutsideToneTest {
    @Test
    fun lengthFollowsTheDuration() {
        assertEquals(OutsideTone.SAMPLE_RATE * 1500 / 1000, OutsideTone.chime(1500, 0.3).size)
        assertEquals(OutsideTone.SAMPLE_RATE * 200 / 1000, OutsideTone.chime(200, 0.3).size)
    }

    @Test
    fun peakNeverExceedsTheRequestedShareOfFullScale() {
        val limit = (0.3 * Short.MAX_VALUE).toInt() + 1
        assertTrue(OutsideTone.chime(1500, 0.3).all { abs(it.toInt()) <= limit })
    }

    @Test
    fun itIsNotSilentAndStartsAndEndsGently() {
        val pcm = OutsideTone.chime(1500, 0.5)
        assertTrue(pcm.any { abs(it.toInt()) > 1_000 })
        assertTrue(abs(pcm.first().toInt()) < 50)
        assertTrue(abs(pcm.last().toInt()) < 50)
    }

    @Test
    fun theSteadyToneKeepsItsLevelForTheWholeDuration() {
        val pcm = OutsideTone.steady(10_000, 0.4)
        assertEquals(OutsideTone.SAMPLE_RATE * 10, pcm.size)
        fun peak(from: Int, to: Int) = (from until to).maxOf { abs(pcm[it].toInt()) }
        val second = OutsideTone.SAMPLE_RATE
        val early = peak(second, 2 * second)
        val late = peak(9 * second - second / 2, 9 * second + second / 2)
        assertTrue("early $early late $late", late > early * 0.9)
        assertTrue(abs(pcm.last().toInt()) < 50)
    }

    @Test
    fun anOutOfRangeAmplitudeIsClamped() {
        assertTrue(OutsideTone.chime(300, 5.0).all { abs(it.toInt()) <= Short.MAX_VALUE })
        assertTrue(OutsideTone.chime(300, -1.0).all { it.toInt() == 0 })
    }
}

package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSensitivityTest {
    @Test
    fun theMiddleStepIsTheLevelsTheAppWasFittedTo() {
        assertEquals(1.0, VoiceSensitivity.factor(0), 0.0)
        assertEquals(1_500, VoiceSensitivity.utterancePeak(0))
        assertEquals(1_200, VoiceSensitivity.bargeInRms(0))
        assertEquals(0, VoiceSensitivity.DEFAULT)
    }

    @Test
    fun aHigherStepNeedsASofterSoundAndALowerOneAFirmerSound() {
        for (step in VoiceSensitivity.RANGE.first until VoiceSensitivity.RANGE.last) {
            assertTrue("$step", VoiceSensitivity.utterancePeak(step) > VoiceSensitivity.utterancePeak(step + 1))
            assertTrue("$step", VoiceSensitivity.bargeInRms(step) > VoiceSensitivity.bargeInRms(step + 1))
        }
    }

    @Test
    fun aStepIsAboutAsixthOfTheLevel() {
        assertEquals(1_304, VoiceSensitivity.utterancePeak(1))
        assertEquals(1_725, VoiceSensitivity.utterancePeak(-1))
        assertEquals(1_043, VoiceSensitivity.bargeInRms(1))
    }

    @Test
    fun theMostSensitiveStepKeepsTalkingOverTheAssistantAboutAtTheJunkFloor() {
        // Below about 800 weak triggers produced junk transcripts and cut replies off for nothing.
        val top = VoiceSensitivity.bargeInRms(VoiceSensitivity.RANGE.last)
        assertTrue("$top", top in 780..820)
    }

    @Test
    fun aStoredStepFromAnywhereIsHeldToTheSlider() {
        assertEquals(-3, VoiceSensitivity.clamp(-40))
        assertEquals(3, VoiceSensitivity.clamp(40))
        assertEquals(VoiceSensitivity.utterancePeak(3), VoiceSensitivity.utterancePeak(99))
        assertEquals(VoiceSensitivity.bargeInRms(-3), VoiceSensitivity.bargeInRms(-99))
    }

    @Test
    fun theDetectorBarIsMovedByTheSetting() {
        // A burst of 1,000 over a quiet cabin and no echo: not talking over the assistant at the middle step, is at the top.
        fun triggers(step: Int): Boolean {
            val detector = BargeInDetector()
            detector.minRms = VoiceSensitivity.bargeInRms(step)
            repeat(20) { detector.onChunk(30, 0) }
            detector.startReply()
            repeat(20) { detector.onChunk(40, 5_000) }
            return (0 until 12).any { detector.onChunk(1_000, 5_000) }
        }
        assertEquals(false, triggers(0))
        assertEquals(true, triggers(3))
    }
}

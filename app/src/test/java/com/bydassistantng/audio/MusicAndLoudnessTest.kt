package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The music choice of a live broadcast and how its loudness levels are turned into gain and boost. */
class MusicAndLoudnessTest {
    @Test
    fun theThreeChoicesAreNamedByTheirWords() {
        assertEquals(MusicMode.PAUSED, MusicMode.from("paused"))
        assertEquals(MusicMode.OUTSIDE, MusicMode.from("outside"))
        assertEquals(MusicMode.INSIDE, MusicMode.from("inside"))
        assertNull(MusicMode.from("loud"))
        assertNull(MusicMode.from(null))
    }

    @Test
    fun theSavedWordWinsOverTheOlderOnOffSetting() {
        assertEquals(MusicMode.INSIDE, MusicMode.fromSaved("inside", true))
        assertEquals(MusicMode.PAUSED, MusicMode.fromSaved("paused", true))
    }

    @Test
    fun theOlderMusicOutsideSettingIsStillReadWhenTheThreeWayChoiceWasNeverMade() {
        assertEquals(MusicMode.OUTSIDE, MusicMode.fromSaved(null, true))
        assertEquals(MusicMode.PAUSED, MusicMode.fromSaved(null, false))
        assertEquals(MusicMode.PAUSED, MusicMode.fromSaved(null, null))
        assertEquals("a word nobody knows falls back to the older setting", MusicMode.OUTSIDE, MusicMode.fromSaved("somewhere", true))
    }

    @Test
    fun theDefaultIsToPauseTheMusic() {
        assertEquals(MusicMode.PAUSED, MusicMode.DEFAULT)
    }

    @Test
    fun theLevelsRiseAndTheLowestLeavesTheVoiceAsItIs() {
        val low = BroadcastLoudness.mediaGain(ExteriorVolume.LOW)
        val medium = BroadcastLoudness.mediaGain(ExteriorVolume.MEDIUM)
        val high = BroadcastLoudness.mediaGain(ExteriorVolume.HIGH)

        assertEquals("the lowest is no gain at all", 1.0f, low, 0.0001f)
        assertTrue("then 6 dB", medium > low && Math.abs(20 * Math.log10(medium.toDouble()) - 6.0) < 0.1)
        assertTrue("then 12 dB", high > medium && Math.abs(20 * Math.log10(high.toDouble()) - 12.0) < 0.1)
        assertTrue("and never more than the chain takes", high <= TalkChain.MAX_GAIN)
    }

    @Test
    fun theNavigationRouteBoostsInSmallerStepsThanTheAssistantsReplies() {
        val low = BroadcastLoudness.boostMb(ExteriorVolume.LOW)
        val medium = BroadcastLoudness.boostMb(ExteriorVolume.MEDIUM)
        val high = BroadcastLoudness.boostMb(ExteriorVolume.HIGH)

        assertTrue("rising", low < medium && medium < high)
        for (level in ExteriorVolume.entries) {
            assertTrue("${level.word}: no more than the assistant's own boost of that level", BroadcastLoudness.boostMb(level) <= level.boostMb)
        }
        assertEquals("the highest is what the assistant's middle level is", ExteriorVolume.MEDIUM.boostMb, high)
    }

    @Test
    fun theMiddleLevelIsTheDefault() {
        assertEquals(ExteriorVolume.MEDIUM, BroadcastLoudness.DEFAULT)
    }
}

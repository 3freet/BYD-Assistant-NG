package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceVolumeTest {
    // The car's voice stream: 5 to 24.
    private fun index(noise: Double, gain: Int = 0) = VoiceVolume.indexFor(noise, gain, min = 5, max = 24)

    @Test
    fun aQuietCabinGetsTheLowestLevelsOfTheStream() {
        val quiet = index(-62.0)
        assertTrue("$quiet", quiet in 5..7)
    }

    @Test
    fun aVeryLoudCabinGetsTheTopOfTheStream() {
        assertEquals(24, index(-20.0))
        assertEquals(24, index(-20.0, gain = 2))
    }

    @Test
    fun theCurveIsTheFittedLineMinusTheBaseOffset() {
        // The fitted line runs from index 5 at -62 dBFS to 24 at -22 dBFS; the whole curve sits BASE_OFFSET_STEPS lower.
        for (noise in listOf(-48.0, -42.4, -40.5, -38.2, -30.0, -27.0)) {
            val line = 5 + 19 * (noise + 62) / 40
            val expected = Math.round(line + VoiceVolume.BASE_OFFSET_STEPS).toInt().coerceIn(5, 24)
            assertEquals("$noise", expected, index(noise))
        }
    }

    @Test
    fun theVolumeRisesWithTheNoiseAndNeverFalls() {
        val levels = (-90..-10 step 2).map { index(it.toDouble()) }
        assertEquals(levels.sorted(), levels)
        assertTrue(levels.first() < levels.last())
    }

    @Test
    fun theGainMovesTheVolumeInStepsAndStaysInsideTheStream() {
        val base = index(-47.0)
        assertEquals(base + 3, index(-47.0, gain = 3))
        assertEquals(base - 3, index(-47.0, gain = -3))
        assertEquals(24, index(-20.0, gain = 6))
        assertEquals(5, VoiceVolume.indexFor(-90.0, -6, min = 5, max = 8))
    }

    @Test
    fun aGainOutsideTheSliderIsHeldToTheSlider() {
        assertEquals(index(-47.0, gain = 6), index(-47.0, gain = 40))
        assertEquals(index(-47.0, gain = -6), index(-47.0, gain = -40))
    }

    @Test
    fun aStreamWithNoRangeGivesItsOnlyLevel() {
        assertEquals(7, VoiceVolume.indexFor(-40.0, 3, min = 7, max = 7))
    }

    @Test
    fun inACalmCabinTheSliderTakesVolumeOffTheTrackOnceTheStreamIsAtItsLowest() {
        val calm = VoiceVolume.levelFor(-62.0, 0, min = 5, max = 24)
        val calmQuieter = VoiceVolume.levelFor(-62.0, -6, min = 5, max = 24)
        val calmQuietest = VoiceVolume.levelFor(-62.0, -3, min = 5, max = 24)
        assertEquals(5, calmQuieter.index)
        assertTrue("-6 is quieter than -3: ${calmQuietest.attenuationDb} vs ${calmQuieter.attenuationDb}", calmQuieter.attenuationDb > calmQuietest.attenuationDb)
        assertTrue(calmQuietest.attenuationDb > calm.attenuationDb || calm.attenuationDb == 0.0)
        // The curve at -62 dBFS is the lowest index plus the base offset; every step the slider goes below it is 2 dB.
        val wanted = 5 + VoiceVolume.BASE_OFFSET_STEPS - 6
        assertEquals((5 - wanted) * VoiceVolume.DB_PER_STEP_BELOW_FLOOR, calmQuieter.attenuationDb, 0.01)
    }

    @Test
    fun aLevelAtOrAboveTheLowestIndexTakesNothingOffTheTrack() {
        val level = VoiceVolume.levelFor(-30.0, 0, min = 5, max = 24)
        assertEquals(0.0, level.attenuationDb, 0.0)
        assertEquals(1f, level.trackVolume, 0f)
    }

    @Test
    fun theTrackVolumeFollowsTheDecibelsAndTheMostTheSliderCanTakeOffIsWithinTheLimit() {
        assertEquals(0.5f, VoiceVolume.Level(5, 6.0206).trackVolume, 0.001f)
        // The calmest cabin with the slider all the way down: the offset and the slider, below the lowest index.
        val extreme = VoiceVolume.levelFor(-100.0, -6, min = 5, max = 24)
        assertEquals((5 - (5 + VoiceVolume.BASE_OFFSET_STEPS - 6)) * VoiceVolume.DB_PER_STEP_BELOW_FLOOR, extreme.attenuationDb, 0.01)
        assertTrue(extreme.attenuationDb <= VoiceVolume.MAX_ATTENUATION_DB)
    }
}

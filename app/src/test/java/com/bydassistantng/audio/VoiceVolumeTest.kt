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
    fun aVeryLoudCabinGetsTheUpperMiddleOfTheStream() {
        assertTrue("${index(-20.0)}", index(-20.0) in 12..17)
        // The slider's +6 is where +4 was: from there it does not reach the top of the stream.
        assertTrue("${index(-20.0, gain = 6)}", index(-20.0, gain = 6) < 24)
    }

    @Test
    fun theCurveIsTheFittedLineMinusTheBaseOffsetUpToTheKnee() {
        // The fitted line runs from index 5 at -62 dBFS to 24 at -22 dBFS; the whole curve sits BASE_OFFSET_STEPS lower.
        for (noise in listOf(-62.0, -58.0, -55.0)) {
            val line = 5 + 19 * (noise + 62) / 40
            val expected = Math.round(line + VoiceVolume.BASE_OFFSET_STEPS).toInt().coerceIn(5, 24)
            assertEquals("$noise", expected, index(noise))
        }
    }

    @Test
    fun aboveTheKneeTheVoiceRisesMoreSlowlyThanTheFittedLineAndTheRoadTrimIsTakenOff() {
        for (noise in listOf(-44.0, -40.0, -36.0, -32.0)) {
            val heard = VoiceVolume.KNEE_DBFS + (noise - VoiceVolume.KNEE_DBFS) * VoiceVolume.ABOVE_KNEE_SLOPE
            val trim = VoiceVolume.roadTrim(noise, VoiceVolume.ROAD_TRIM_STEPS)
            val expected = Math.round(5 + 19 * (heard + 62) / 40 + VoiceVolume.BASE_OFFSET_STEPS - trim).toInt()
            assertEquals("$noise", expected, index(noise))
        }
    }

    @Test
    fun theRoadTrimGrowsFromNothingInACalmCabinToTheWholeAmountOnTheRoad() {
        assertEquals(0.0, VoiceVolume.roadTrim(-62.0, 2.0), 0.0)
        assertEquals(0.0, VoiceVolume.roadTrim(VoiceVolume.ROAD_TRIM_FROM_DBFS, 2.0), 0.0)
        assertEquals(1.0, VoiceVolume.roadTrim(-50.0, 2.0), 0.0001)
        assertEquals(2.0, VoiceVolume.roadTrim(VoiceVolume.ROAD_TRIM_FULL_DBFS, 2.0), 0.0)
        assertEquals(2.0, VoiceVolume.roadTrim(-30.0, 2.0), 0.0)
    }

    @Test
    fun theMotorwayLevelsOfTheDriveAreQuieterThanTheyWere() {
        // 2026-10-18, the line as it was: 16 at -40 dBFS, 18 to 19 at -36 to -34. After the bend 14, 15. Then the road trim
        // (two steps) and the slider's zero two steps lower: 10 and 11 at slider 0.
        assertTrue("${index(-40.0)}", index(-40.0) in 9..11)
        assertTrue("${index(-35.0)}", index(-35.0) in 10..12)
        assertTrue(index(-40.0) > index(-55.0))
    }

    @Test
    fun inACalmCabinTheRoadTrimDoesNothingAndTheLineIsTheFittedOneMinusTheBase() {
        for (noise in listOf(-70.0, -62.0, -58.0, -55.0)) {
            val line = 5 + 19 * (noise + 62).coerceAtLeast(0.0) / 40
            assertEquals("$noise", Math.round(line + VoiceVolume.BASE_OFFSET_STEPS).toInt().coerceIn(5, 24), index(noise))
        }
    }

    @Test
    fun theSlidersZeroSitsTwoStepsBelowWhereItWas() {
        // Before the shift the base was +1; the labels -6 to +6 are the same, two steps lower.
        assertEquals(-1, VoiceVolume.BASE_OFFSET_STEPS)
        assertEquals(Math.round(5 + 19 * (-55 + 62) / 40.0 + 1 - 2).toInt(), index(-55.0))
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
        assertEquals(8, VoiceVolume.indexFor(-20.0, 6, min = 5, max = 8))
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

    // The navigation stream, which the headrest speaker is reached on: 0 to 10 (index 0 is silence).
    private fun headrest(noise: Double, gain: Int = 0) = VoiceVolume.headrestLevelFor(noise, gain, min = 0, max = 10)

    @Test
    fun aCalmCabinGetsTheLowIndexInTheHeadrest() {
        assertEquals(3, headrest(-68.0).index)
        assertEquals(3, headrest(-62.0).index)
        assertEquals(0.0, headrest(-62.0).attenuationDb, 0.0)
    }

    @Test
    fun aLoudCabinGetsTheUpperPartOfTheNavigationStreamAndTheSliderCanTakeItToTheTop() {
        assertTrue("${headrest(-32.0).index}", headrest(-32.0).index in 6..8)
        assertEquals(10, headrest(-20.0, gain = 4).index)
    }

    @Test
    fun theHeadrestLineBendsAtTheKneeToo() {
        // 2026-10-18, the line as it was: 8 at -41 dBFS, 9 at -36, 10 at -32. A step or two lower at speed, the same below.
        assertTrue("${headrest(-41.0).index}", headrest(-41.0).index in 5..7)
        assertTrue("${headrest(-36.0).index}", headrest(-36.0).index in 6..8)
        assertTrue("${headrest(-32.0).index}", headrest(-32.0).index <= 8)
    }

    @Test
    fun musicTakesPartOfTheFirstReadingOffAccordingToHowFarItIsTurnedDown() {
        // Half down is 6 dB; 0.7 of it counts.
        assertEquals(4.2, VoiceVolume.musicAllowanceDb(MusicDuck.LEVEL, 50), 0.05)
        assertTrue(VoiceVolume.musicAllowanceDb(MusicDuck.LEVEL, 80) > VoiceVolume.musicAllowanceDb(MusicDuck.LEVEL, 30))
        assertEquals(VoiceVolume.MAX_MUSIC_ALLOWANCE_DB, VoiceVolume.musicAllowanceDb(MusicDuck.LEVEL, 90), 0.0)
        assertEquals(0.0, VoiceVolume.musicAllowanceDb(MusicDuck.KEEP, 50), 0.0)
        assertTrue(VoiceVolume.musicAllowanceDb(MusicDuck.PAUSE, 50) > 0.0)
        assertEquals(VoiceVolume.musicAllowanceDb(MusicDuck.LOWER, 50), VoiceVolume.musicAllowanceDb(MusicDuck.PAUSE, 50), 0.0)
    }

    @Test
    fun theFirstReplyWithMusicIsAsLoudAsTheNextOnes() {
        // The drive: -36.8 read before the dip, then -40 once the music was down. With the allowance they land together.
        val first = index(-36.8 - VoiceVolume.musicAllowanceDb(MusicDuck.LEVEL, 50))
        val next = index(-40.0)
        assertTrue("$first vs $next", kotlin.math.abs(first - next) <= 1)
    }

    @Test
    fun theHeadrestVolumeRisesWithTheNoiseAndNeverGoesBackDown() {
        val levels = (-70..-20 step 2).map { headrest(it.toDouble()).index }
        assertEquals(levels.sorted(), levels)
    }

    @Test
    fun theHeadrestSliderMovesItAndNeverReachesSilence() {
        assertEquals(headrest(-47.0).index - 2, headrest(-47.0, gain = -2).index)
        // Far down the slider in a calm cabin: the stream stays at its first audible index and the track is made quieter.
        val lowest = headrest(-68.0, gain = -6)
        assertEquals(VoiceVolume.HEADREST_FLOOR_INDEX, lowest.index)
        assertTrue(lowest.attenuationDb > 0.0)
        assertTrue(lowest.trackVolume < 1f)
        assertEquals(VoiceVolume.HEADREST_FLOOR_INDEX, headrest(-68.0, gain = -4).index.coerceAtLeast(1))
    }
}

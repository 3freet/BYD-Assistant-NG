package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChannelOptionsTest {
    @Test
    fun theMusicChoiceIsStoredByItsWordAndAnythingElseFallsBackToTurningItDownByAShare() {
        MusicDuck.entries.forEach { assertEquals(it, MusicDuck.from(it.key)) }
        assertEquals(MusicDuck.LEVEL, MusicDuck.from(null))
        assertEquals(MusicDuck.LEVEL, MusicDuck.from("loud"))
    }

    @Test
    fun theGainIsHeldToTheSliderRange() {
        assertEquals(-6, VoiceChannelOptions.clampGain(-30))
        assertEquals(6, VoiceChannelOptions.clampGain(30))
        assertEquals(2, VoiceChannelOptions.clampGain(2))
    }

    @Test
    fun theChannelIsOffUntilSwitchedOn() {
        assertFalse(VoiceChannelOptions.OFF.enabled)
        assertFalse(VoiceChannelOptions.OFF.adaptive)
    }

    @Test
    fun theDipIsHeldToTheSliderRange() {
        assertEquals(10, VoiceChannelOptions.clampDip(0))
        assertEquals(90, VoiceChannelOptions.clampDip(100))
        assertEquals(50, VoiceChannelOptions.clampDip(50))
    }

    @Test
    fun turningTheMusicDownByAShareAlwaysTakesAtLeastOneStepOff() {
        assertEquals(10, MusicDip.target(20, 50))
        assertEquals(2, MusicDip.target(10, 80))
        // A gentle share of a low volume still lowers it by a step.
        assertEquals(2, MusicDip.target(3, 10))
        assertEquals(4, MusicDip.target(5, 10))
        // Nothing to turn down at 0 or 1.
        assertEquals(1, MusicDip.target(1, 90))
        assertEquals(0, MusicDip.target(0, 90))
    }

    @Test
    fun aFadeTakesAboutFourTenthsOfASecondWhateverTheNumberOfSteps() {
        assertEquals(200L, MusicDip.rampStepMs(1))
        assertEquals(200L, MusicDip.rampStepMs(2))
        assertEquals(100L, MusicDip.rampStepMs(4))
        assertEquals(40L, MusicDip.rampStepMs(10))
        assertEquals(20L, MusicDip.rampStepMs(30))
        assertEquals(200L, MusicDip.rampStepMs(0))
    }

    @Test
    fun onlyMediaOrGamePlayersCountAsMusicNotTheAssistantsVoiceOrANavigationPrompt() {
        val media = android.media.AudioAttributes.USAGE_MEDIA
        assertTrue(MusicPlayback.isMusic(media, android.media.AudioAttributes.CONTENT_TYPE_MUSIC))
        assertTrue(MusicPlayback.isMusic(media, android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN))
        assertTrue(MusicPlayback.isMusic(android.media.AudioAttributes.USAGE_GAME, 0))
        assertFalse(MusicPlayback.isMusic(media, MusicPlayback.CONTENT_TYPE_VOICE))
        assertFalse(MusicPlayback.isMusic(media, MusicPlayback.CONTENT_TYPE_NAVI))
        assertFalse(MusicPlayback.isMusic(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, 4))
        assertFalse(MusicPlayback.isMusic(android.media.AudioAttributes.USAGE_ASSISTANT, 1))
    }
}

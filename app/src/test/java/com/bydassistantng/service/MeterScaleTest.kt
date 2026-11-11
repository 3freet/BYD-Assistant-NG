package com.bydassistantng.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the level meter turns a level in dB into a height. */
class MeterScaleTest {
    @Test
    fun silenceIsNothingAndTheTopOfTheScaleIsFull() {
        assertEquals(0f, MeterScale.voice(-90f), 0.0001f)
        assertEquals(0f, MeterScale.voice(-60f), 0.0001f)
        assertEquals(1f, MeterScale.voice(-10f), 0.0001f)
        assertEquals(1f, MeterScale.voice(0f), 0.0001f)
    }

    @Test
    fun aQuietVoiceStillShowsAndALouderOneShowsMore() {
        val faded = MeterScale.voice(-48f) // the voice as it went out with the tail fade closed on it
        val normal = MeterScale.voice(-30f)
        val loud = MeterScale.voice(-18f)
        assertTrue("faded $faded is visible", faded > 0.2f)
        assertTrue("normal $normal is clearly more than faded $faded", normal > faded + 0.25f)
        assertTrue("loud $loud is more still", loud > normal)
    }

    @Test
    fun theFeedbackBandIsFlatForAnEchoUnderTheNoiseAndGrowsAsItRises() {
        assertEquals(0f, MeterScale.echo(-90f), 0.0001f)
        assertEquals(0f, MeterScale.echo(-80f), 0.0001f)
        assertEquals(1f, MeterScale.echo(-40f), 0.0001f)
        assertTrue(MeterScale.echo(-60f) < MeterScale.echo(-50f))
        assertTrue("a healthy car's echo (about -72 dB) is a sliver (${MeterScale.echo(-72f)})", MeterScale.echo(-72f) < 0.2f)
    }
}

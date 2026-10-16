package com.bydassistantng.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechBandTest {
    private val rate = 16_000

    private fun tone(hz: Double, amplitude: Double = 8000.0, ms: Int = 1000) =
        ShortArray(rate * ms / 1000) { (amplitude * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    /** The speech band's level for [samples] fed in 50 ms windows, as the monitor does, taking the last window. */
    private fun speechLevel(samples: ShortArray): Double {
        val band = SpeechBand(rate)
        val window = rate * 50 / 1000
        var level = 0.0
        for (start in 0 until samples.size - window + 1 step window) level = band.dbfs(samples.copyOfRange(start, start + window))
        return level
    }

    @Test
    fun aToneInTheMiddleOfTheSpeechBandPassesAlmostUntouched() {
        val samples = tone(1000.0)
        assertEquals(CabinNoise.dbfs(samples), speechLevel(samples), 1.5)
    }

    @Test
    fun lowRumbleIsHeldBackFarMoreThanSpeech() {
        val rumble = tone(80.0)
        val voice = tone(1000.0)
        val rumbleDrop = CabinNoise.dbfs(rumble) - speechLevel(rumble)
        val voiceDrop = CabinNoise.dbfs(voice) - speechLevel(voice)
        assertTrue("rumble dropped $rumbleDrop dB", rumbleDrop > 20.0)
        assertTrue("voice dropped $voiceDrop dB", voiceDrop < 2.0)
    }

    @Test
    fun hissAboveTheBandIsHeldBackToo() {
        val hiss = tone(7000.0)
        assertTrue(CabinNoise.dbfs(hiss) - speechLevel(hiss) > 10.0)
    }

    @Test
    fun silenceStaysSilence() {
        assertEquals(CabinNoise.SILENCE_DBFS, SpeechBand(rate).dbfs(ShortArray(800)), 0.0)
        assertEquals(CabinNoise.SILENCE_DBFS, SpeechBand(rate).dbfs(ShortArray(0)), 0.0)
    }

    @Test
    fun theFilterCarriesOnFromOneWindowToTheNext() {
        // The same tone cut into windows reads the same as it does in one piece, once settled.
        val samples = tone(500.0, ms = 2000)
        val cut = speechLevel(samples)
        val whole = SpeechBand(rate).also { it.dbfs(samples.copyOfRange(0, 16_000)) }.dbfs(samples.copyOfRange(16_000, 16_800))
        assertEquals(whole, cut, 0.5)
    }
}

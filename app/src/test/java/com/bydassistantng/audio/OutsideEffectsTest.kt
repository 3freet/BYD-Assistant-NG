package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OutsideEffectsTest {
    private val full = Short.MAX_VALUE.toDouble()

    @Test
    fun everyEffectInTheCatalogCanBeRendered() {
        for (effect in OutsideEffects.catalog) {
            assertNotNull("no rendering for ${effect.name}", OutsideEffects.render(effect.name))
        }
    }

    @Test
    fun anUnknownNameIsNull() {
        assertNull(OutsideEffects.render("air raid siren"))
    }

    @Test
    fun namesAreUniqueAndHaveDescriptions() {
        assertEquals(OutsideEffects.catalog.size, OutsideEffects.catalog.map { it.name }.toSet().size)
        assertTrue(OutsideEffects.catalog.all { it.description.isNotBlank() })
    }

    @Test
    fun noEffectImpersonatesAnEmergencyVehicle() {
        assertTrue(OutsideEffects.catalog.none { it.name.contains("siren") || it.name.contains("police") })
    }

    @Test
    fun effectsAreAudibleBoundedAndShortAndEndGently() {
        for (effect in OutsideEffects.catalog) {
            val pcm = OutsideEffects.render(effect.name)!!
            val seconds = pcm.size.toDouble() / OutsideEffects.SAMPLE_RATE
            assertTrue("${effect.name} is $seconds s", seconds in 0.3..3.5)
            val peak = pcm.maxOf { abs(it.toInt()) } / full
            assertTrue("${effect.name} peak $peak", peak in 0.85..0.93)
            assertTrue("${effect.name} ends loud", abs(pcm.last().toInt()) < 200)
            assertTrue("${effect.name} starts loud", abs(pcm.first().toInt()) < 2_000)
        }
    }

    @Test
    fun effectsAreNotFlatNoiseOrSilence() {
        for (effect in OutsideEffects.catalog) {
            val pcm = OutsideEffects.render(effect.name)!!
            val loud = pcm.count { abs(it.toInt()) > 0.1 * full }
            assertTrue("${effect.name} is mostly quiet ($loud loud samples)", loud > pcm.size / 50)
        }
    }
}

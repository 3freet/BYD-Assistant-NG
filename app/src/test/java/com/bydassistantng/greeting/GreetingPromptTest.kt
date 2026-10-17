package com.bydassistantng.greeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingPromptTest {
    private fun build(
        part: GreetingPrompt.PartOfDay = GreetingPrompt.PartOfDay.MORNING,
        time: GreetingPrompt.TimeOfDay = GreetingPrompt.TimeOfDay(8, 5),
        celsius: Int? = 24,
        language: String? = null,
        notes: Boolean = false,
    ) = GreetingPrompt.build(part, time, celsius, language, notes)

    @Test
    fun theHourDecidesThePartOfTheDay() {
        assertEquals(GreetingPrompt.PartOfDay.NIGHT, GreetingPrompt.partOfDay(4))
        assertEquals(GreetingPrompt.PartOfDay.MORNING, GreetingPrompt.partOfDay(5))
        assertEquals(GreetingPrompt.PartOfDay.MORNING, GreetingPrompt.partOfDay(11))
        assertEquals(GreetingPrompt.PartOfDay.AFTERNOON, GreetingPrompt.partOfDay(12))
        assertEquals(GreetingPrompt.PartOfDay.AFTERNOON, GreetingPrompt.partOfDay(16))
        assertEquals(GreetingPrompt.PartOfDay.EVENING, GreetingPrompt.partOfDay(17))
        assertEquals(GreetingPrompt.PartOfDay.EVENING, GreetingPrompt.partOfDay(21))
        assertEquals(GreetingPrompt.PartOfDay.NIGHT, GreetingPrompt.partOfDay(22))
        assertEquals(GreetingPrompt.PartOfDay.NIGHT, GreetingPrompt.partOfDay(0))
    }

    @Test
    fun saysThatItIsNotTheDriver() {
        val text = build()
        assertTrue(text.contains("not the driver speaking"))
        assertTrue(text.contains("a good morning"))
        assertTrue(text.contains("24 degrees Celsius"))
    }

    @Test
    fun theDefaultIsTheWishThenTheTimeThenTheTemperatureAndNothingElse() {
        val text = build()
        assertTrue(text.contains("Nothing else: do not ask them anything"))
        assertTrue(text.contains("do not offer help"))
        val wish = text.indexOf("a good morning")
        val time = text.indexOf("the time (it is 08:05")
        val temperature = text.indexOf("24 degrees Celsius")
        assertTrue("$wish $time $temperature", wish in 0 until time && time < temperature)
    }

    @Test
    fun tellsTheTimeEvenWithoutATemperature() {
        val text = build(celsius = null, time = GreetingPrompt.TimeOfDay(14, 30))
        assertTrue(text.contains("it is 14:30 on the 24-hour clock"))
        assertFalse(text.contains("Celsius"))
    }

    @Test
    fun theTimeIsPaddedLikeAClock() {
        assertEquals("07:05", GreetingPrompt.TimeOfDay(7, 5).toString())
        assertEquals("00:00", GreetingPrompt.TimeOfDay(0, 0).toString())
        assertEquals("23:59", GreetingPrompt.TimeOfDay(23, 59).toString())
    }

    @Test
    fun asksForAWarmOptimisticVoiceNotAFlatOne() {
        val text = build()
        assertTrue(text.contains("Say it warmly"))
        assertTrue(text.contains("upbeat and optimistic"))
        assertTrue(text.contains("never flat or bored"))
    }

    @Test
    fun asksForItToBeBrief() {
        val text = build()
        assertTrue(text.contains("Keep it brief"))
        assertTrue(text.contains("about a dozen words"))
        assertTrue(text.contains("no filler or small talk"))
    }

    @Test
    fun leavesOutTheTemperatureTheCarDidNotGive() {
        val text = build(GreetingPrompt.PartOfDay.EVENING, celsius = null)
        assertFalse(text.contains("Celsius"))
        assertTrue(text.contains("a good evening"))
    }

    @Test
    fun nightIsAPlainHello() {
        val text = build(GreetingPrompt.PartOfDay.NIGHT)
        assertTrue(text.contains("a hello"))
        assertFalse(text.contains("good night"))
    }

    @Test
    fun namesTheLanguageOnlyWhenOneIsGiven() {
        assertFalse(build().contains("Speak in"))
        assertTrue(build(language = "Arabic").contains("Speak in Arabic."))
    }

    @Test
    fun rememberedNotesMayChangeTheGreetingOnlyWhenThereAreNotes() {
        assertFalse(build(notes = false).contains("remembered note"))
        val withNotes = build(notes = true)
        assertTrue(withNotes.contains("remembered notes says how this driver wants to be greeted"))
        assertTrue(withNotes.contains("ask them something"))
    }
}

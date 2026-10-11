package com.bydassistantng.audio

import com.bydassistantng.gemini.GeminiFunctionCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakOutsideToolTest {
    private fun call(args: JsonObject) = GeminiFunctionCall(name = SpeakOutsideTool.FUNCTION_NAME, args = args)

    @Test
    fun theMessageIsTrimmedAndRequired() {
        assertEquals("Please wait", SpeakOutsideTool.message(call(buildJsonObject { put("message", "  Please wait \n") })))
        assertNull(SpeakOutsideTool.message(call(buildJsonObject { put("message", "   ") })))
        assertNull(SpeakOutsideTool.message(call(JsonObject(emptyMap()))))
    }

    @Test
    fun aVeryLongMessageIsCut() {
        val long = "a".repeat(5_000)
        assertEquals(400, SpeakOutsideTool.message(call(buildJsonObject { put("message", long) }))?.length)
    }

    @Test
    fun readyTellsTheModelToSayTheWordsNow() {
        val answer = SpeakOutsideTool.ready("I'm coming")
        assertEquals("ok", (answer["status"] as JsonPrimitive).content)
        assertTrue((answer["instruction"] as JsonPrimitive).content.contains("\"I'm coming\""))
    }

    @Test
    fun refusalIsAnError() {
        val answer = SpeakOutsideTool.refused("not available")
        assertEquals("error", (answer["status"] as JsonPrimitive).content)
        assertTrue((answer["error"] as JsonPrimitive).content.contains("not available"))
    }

    @Test
    fun theDeclarationAsksForAMessageAndOffersALevel() {
        assertEquals(SpeakOutsideTool.FUNCTION_NAME, SpeakOutsideTool.declaration.name)
        assertEquals(listOf("message"), SpeakOutsideTool.declaration.parameters?.required)
        val levels = SpeakOutsideTool.declaration.parameters?.properties?.get("volume")?.enum
        assertEquals(listOf("low", "medium", "high"), levels)
    }

    @Test
    fun theLevelIsOnlyTakenWhenTheModelNamedOne() {
        assertEquals(ExteriorVolume.HIGH, SpeakOutsideTool.volume(call(buildJsonObject { put("message", "x"); put("volume", "high") })))
        assertEquals(ExteriorVolume.LOW, SpeakOutsideTool.volume(call(buildJsonObject { put("message", "x"); put("volume", " LOW ") })))
        assertNull(SpeakOutsideTool.volume(call(buildJsonObject { put("message", "x") })))
        assertNull(SpeakOutsideTool.volume(call(buildJsonObject { put("message", "x"); put("volume", "ear-splitting") })))
    }
}

class PlaySoundOutsideToolTest {
    private val effects = listOf(SoundEffect("doorbell", "a ding-dong"), SoundEffect("cat", "a meow"))
    private fun call(args: JsonObject) = GeminiFunctionCall(name = PlaySoundOutsideTool.FUNCTION_NAME, args = args)

    @Test
    fun theDeclarationListsOnlyTheSoundsThatExist() {
        val declaration = PlaySoundOutsideTool.declaration(effects)
        assertEquals(listOf("sound"), declaration.parameters?.required)
        assertEquals(listOf("doorbell", "cat"), declaration.parameters?.properties?.get("sound")?.enum)
        assertTrue(declaration.description.contains("doorbell"))
        assertTrue(declaration.description.contains("a meow"))
    }

    @Test
    fun theSoundIsLookedUpByNameIgnoringCase() {
        assertEquals(effects[1], PlaySoundOutsideTool.effect(call(buildJsonObject { put("sound", " Cat ") }), effects))
        assertNull(PlaySoundOutsideTool.effect(call(buildJsonObject { put("sound", "siren") }), effects))
        assertNull(PlaySoundOutsideTool.effect(call(JsonObject(emptyMap())), effects))
    }

    @Test
    fun theAnswersAreStatusObjects() {
        assertEquals("ok", (PlaySoundOutsideTool.playing(effects[0])["status"] as JsonPrimitive).content)
        assertEquals("error", (PlaySoundOutsideTool.refused("no")["status"] as JsonPrimitive).content)
    }
}

class ExteriorVolumeTest {
    @Test
    fun everyLevelIsBoostedAndEachIsLouderThanTheLast() {
        val boosts = ExteriorVolume.entries.map { it.boostMb }
        assertTrue(boosts.all { it > 0 })
        assertEquals(boosts.sorted(), boosts)
        assertEquals(boosts.size, boosts.toSet().size)
    }

    @Test
    fun highIsTheLoudestBoostThatWasTried() {
        assertEquals(6_000, ExteriorVolume.HIGH.boostMb)
        assertEquals(ExteriorVolume.entries.maxOf { it.boostMb }, ExteriorVolume.HIGH.boostMb)
    }

    @Test
    fun aLevelIsFoundByItsWord() {
        assertEquals(ExteriorVolume.MEDIUM, ExteriorVolume.from("Medium"))
        assertNull(ExteriorVolume.from("max"))
        assertNull(ExteriorVolume.from(null))
        assertEquals(ExteriorVolume.MEDIUM, ExteriorVolume.DEFAULT)
    }
}

package com.bydassistantng.vehicle

import com.bydassistantng.gemini.GeminiFunctionCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetDriveModeToolTest {
    private fun call(args: JsonObject) = GeminiFunctionCall(name = SetDriveModeTool.FUNCTION_NAME, args = args)

    private fun text(answer: JsonObject, key: String) = (answer[key] as? JsonPrimitive)?.content

    @Test
    fun theDeclarationOffersEveryModeByItsWordAndRequiresOne() {
        val declaration = SetDriveModeTool.declaration
        assertEquals("set_drive_mode", declaration.name)
        assertEquals(listOf("mode"), declaration.parameters?.required)
        val words = declaration.parameters?.properties?.get("mode")?.enum
        assertEquals(DriveMode.entries.map { it.word }, words)
        assertTrue(listOf("snow", "sand", "mud", "mountain", "rock", "auto", "exit").all { it in words.orEmpty() })
    }

    @Test
    fun modesTheCarMustNeverBeAskedForAreNotNameable() {
        val words = SetDriveModeTool.declaration.parameters?.properties?.get("mode")?.enum.orEmpty()
        assertFalse(words.any { it in setOf("wade", "warp", "custom", "traction", "uturn") })
        for (word in listOf("wade", "warp", "custom", "traction", "u-turn", "", "  ")) {
            assertNull(SetDriveModeTool.mode(call(buildJsonObject { put("mode", word) })))
        }
        assertNull(SetDriveModeTool.mode(call(JsonObject(emptyMap()))))
    }

    @Test
    fun theModeIsReadIgnoringCaseAndSpaces() {
        assertEquals(DriveMode.SAND, SetDriveModeTool.mode(call(buildJsonObject { put("mode", " Sand ") })))
        assertEquals(DriveMode.MOUNTAIN, SetDriveModeTool.mode(call(buildJsonObject { put("mode", "MOUNTAIN") })))
        assertEquals(DriveMode.EXIT, SetDriveModeTool.mode(call(buildJsonObject { put("mode", "exit") })))
    }

    @Test
    fun aChangeIsOkAndNamesTheModeTheCarIsIn() {
        val answer = SetDriveModeTool.answer(DriveModeOutcome.Changed(before = "Normal", now = "Sand"))
        assertEquals("ok", text(answer, "status"))
        assertEquals("Sand", text(answer, "drive_mode_now"))
        assertEquals("Normal", text(answer, "drive_mode_before"))
    }

    @Test
    fun everyAnswerSaysWhatToDoNextSoTheModelDoesNotAskAgain() {
        val changed = SetDriveModeTool.answer(DriveModeOutcome.Changed(before = "Eco", now = "Sport"))
        assertTrue(text(changed, "instruction")!!.contains("Sport"))
        assertTrue(text(changed, "instruction")!!.contains("Do not call this function again"))
        val unchanged = SetDriveModeTool.answer(DriveModeOutcome.Unchanged(current = "Eco", detail = "it stayed as it was"))
        assertTrue(text(unchanged, "instruction")!!.contains("Do not try again"))
        assertTrue(text(SetDriveModeTool.refused("moving"), "instruction")!!.contains("Do not try again"))
    }

    @Test
    fun aRepeatedRequestIsAnsweredAsAlreadyDone() {
        val answer = SetDriveModeTool.alreadyDone(DriveModeOutcome.Changed(before = "Eco", now = "Sport"))
        assertEquals("ok", text(answer, "status"))
        assertEquals("Sport", text(answer, "drive_mode_now"))
        assertEquals("true", text(answer, "already_done"))
        assertTrue(text(answer, "instruction")!!.contains("Do not call this function again"))
    }

    @Test
    fun theSameRequestMomentsAfterAConfirmedChangeIsARepeat() {
        val guard = DriveModeRepeatGuard(windowMs = 6_000)
        val first = DriveModeOutcome.Changed(before = "Eco", now = "Sport")
        assertNull(guard.repeatOf(DriveMode.SPORT, nowMs = 1_000))
        guard.record(DriveMode.SPORT, first, nowMs = 1_000)
        assertEquals(first, guard.repeatOf(DriveMode.SPORT, nowMs = 2_800))
        assertEquals(first, guard.repeatOf(DriveMode.SPORT, nowMs = 7_000))
        // Too late: the person may well be asking again.
        assertNull(guard.repeatOf(DriveMode.SPORT, nowMs = 7_001))
        // Another mode is a new request.
        assertNull(guard.repeatOf(DriveMode.ECO, nowMs = 2_000))
    }

    @Test
    fun aRequestThatDidNotChangeAnythingIsNeverRemembered() {
        val guard = DriveModeRepeatGuard(windowMs = 6_000)
        guard.record(DriveMode.SAND, DriveModeOutcome.Changed(before = "Normal", now = "Sand"), nowMs = 0)
        // A later failure for another mode clears what was remembered.
        guard.record(DriveMode.MUD, DriveModeOutcome.Refused("moving"), nowMs = 1_000)
        assertNull(guard.repeatOf(DriveMode.SAND, nowMs = 1_500))
        assertNull(guard.repeatOf(DriveMode.MUD, nowMs = 1_500))
        guard.record(DriveMode.MUD, DriveModeOutcome.Unchanged(current = "Normal", detail = "it stayed as it was"), nowMs = 2_000)
        assertNull(guard.repeatOf(DriveMode.MUD, nowMs = 2_500))
    }

    @Test
    fun aCarThatDidNotSwitchIsAnErrorThatSaysWhereItIs() {
        val answer = SetDriveModeTool.answer(DriveModeOutcome.Unchanged(current = "Normal", detail = "it stayed as it was"))
        assertEquals("error", text(answer, "status"))
        assertTrue(text(answer, "error")!!.contains("it stayed as it was"))
        assertTrue(text(answer, "error")!!.contains("Normal"))
    }

    @Test
    fun aRefusalIsAnErrorWithItsReason() {
        val answer = SetDriveModeTool.answer(DriveModeOutcome.Refused("the car is not stationary"))
        assertEquals("error", text(answer, "status"))
        assertTrue(text(answer, "error")!!.contains("the car is not stationary"))
    }
}

package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAnswersTest {
    @Test
    fun anAcceptedSetterWithNoReadBackSaysItCannotBeCheckedAndNotToRepeat() {
        for (note in listOf("HAL returned 0", "HAL returned 1", "sent, but the car wouldn't report a value to confirm it", null, "  ")) {
            val said = CommandAnswers.forModel(note)!!
            assertEquals(CommandAnswers.UNCHECKED_NOTE, said)
            assertTrue(said.contains("Do not send it again"))
            // The bare code is what made the model think it had failed.
            assertFalse(said.contains("HAL returned"))
        }
    }

    @Test
    fun aMovingWindowIsOnItsWay() {
        val said = CommandAnswers.forModel("under way: now at 41, heading for 70")!!
        assertTrue(said, said.startsWith("On its way: now at 41, heading for 70."))
        assertTrue(said.contains("Do not send it again"))
    }

    @Test
    fun theServicesOwnVerdictIsDone() {
        val said = CommandAnswers.forModel("the service accepted it (answer 1)")!!
        assertTrue(said.startsWith("Done: the service accepted it."))
        assertTrue(said.contains("Do not send it again"))
    }

    @Test
    fun aConfirmedValueIsLeftAsTheCarReportedIt() {
        for (note in listOf("confirmed: the car now reports 2", "confirmed: A/C now reports 23", "confirmed: the volume is now 6 of 39", "stub: no HAL call made")) {
            assertEquals(note, CommandAnswers.forModel(note))
        }
    }

    @Test
    fun theRepeatNoteIsPassedThroughUnchanged() {
        assertEquals(CommandAnswers.REPEAT_NOTE, CommandAnswers.forModel(CommandAnswers.REPEAT_NOTE))
    }
}

package com.bydassistantng.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTextTest {
    @Test
    fun aShortLogIsKeptWhole() {
        val kept = LogText.newest("one\ntwo\n", maxChars = 100)
        assertEquals("one\ntwo\n", kept.text)
        assertFalse(kept.isCut)
    }

    @Test
    fun aLongLogKeepsItsNewestLinesAndOnlyWholeOnes() {
        val log = (1..1000).joinToString("\n") { "line $it" } + "\n"
        val kept = LogText.newest(log, maxChars = 500)
        assertTrue(kept.isCut)
        assertEquals(log.length, kept.totalChars)
        assertTrue(kept.text.length <= 500)
        assertTrue(kept.text.endsWith("line 1000\n"))
        // It starts at the start of a line, not part of one.
        assertTrue(kept.text.startsWith("line "))
        assertEquals(log.takeLast(kept.text.length), kept.text)
    }

    @Test
    fun aLogWithOneHugeLineIsStillCut() {
        val kept = LogText.newest("x".repeat(1000), maxChars = 100)
        assertEquals(100, kept.text.length)
        assertTrue(kept.isCut)
    }

    @Test
    fun theLimitKeepsASharedLogFarUnderWhatOneIntentCanCarry() {
        // Text goes into the parcel as UTF-16: two bytes a character, and the limit for everything in flight is about 1 MB.
        assertTrue(LogText.MAX_CHARS * 2 < 300_000)
    }
}

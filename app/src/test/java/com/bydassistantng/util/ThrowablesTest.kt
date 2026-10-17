package com.bydassistantng.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrowablesTest {
    private fun inner(): Nothing = throw IllegalStateException("inner problem")

    private fun outer() {
        try {
            inner()
        } catch (e: IllegalStateException) {
            throw RuntimeException("Failure from system", e)
        }
    }

    @Test
    fun everyFrameOfEveryCauseIsPrintedAndNothingIsLeftToMoreFrames() {
        val error = try {
            outer()
            error("not reached")
        } catch (e: RuntimeException) {
            e
        }
        val text = error.fullStackTrace()

        // What printStackTrace would have shortened to "... N more" is here in full.
        assertFalse(Regex("\\.\\.\\. \\d+ more").containsMatchIn(text))
        assertTrue(text.startsWith("java.lang.RuntimeException: Failure from system"))
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: inner problem"))
        val causeFrames = error.cause!!.stackTrace.size
        val atLines = text.lines().count { it.startsWith("\tat ") }
        assertEquals(error.stackTrace.size + causeFrames, atLines)
    }

    @Test
    fun aCauseThatPointsBackToItsOwnerDoesNotLoopForever() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val text = a.fullStackTrace()
        assertTrue(text.contains("java.lang.RuntimeException: a"))
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: b"))
    }
}

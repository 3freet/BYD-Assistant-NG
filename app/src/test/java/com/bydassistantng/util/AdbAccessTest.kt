package com.bydassistantng.util

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbAccessTest {
    /** One try as the car answers it: how long the connection took, and whether it came out allowed. */
    private data class Try(val tookMs: Long, val allowed: Boolean)

    /** An access whose clock only moves when a try takes time or the loop pauses, and whose tries are scripted. */
    private class Rig(vararg script: Try) {
        var clock = 0L
        val logs = mutableListOf<String>()
        var tries = 0
        private val remaining = script.toMutableList()
        val access = AdbAccess(
            tryOnce = {
                tries++
                val next = if (remaining.size > 1) remaining.removeAt(0) else remaining.first()
                clock += next.tookMs
                next.allowed
            },
            now = { clock },
            pause = { clock += it },
            log = { logs += it },
        )
    }

    @Test
    fun allowsAtOnceWhenTheCarAnswersAtOnce() = runBlocking {
        val rig = Rig(Try(120, true))

        assertTrue(rig.access.await(10_000))
        assertEquals(1, rig.tries)
        // Nothing to say about a connection that needed no prompt, beyond the one line that it is being checked.
        assertFalse(rig.logs.any { it.contains("after") })
    }

    @Test
    fun waitsForThePromptToBeAnsweredOnOneConnection() = runBlocking {
        // The connection is held for 23 s while the user finds the screen, then comes out allowed.
        val rig = Rig(Try(23_000, true))

        assertTrue(rig.access.await(8_000))
        assertEquals(1, rig.tries)
        assertTrue(rig.logs.last(), rig.logs.last().contains("after 23 s"))
    }

    @Test
    fun doesNotAskAgainOnceItHasBeenAllowed() = runBlocking {
        val rig = Rig(Try(300, true))
        rig.access.await(10_000)

        assertTrue(rig.access.await(10_000))
        assertEquals(1, rig.tries)
    }

    @Test
    fun keepsTryingWhileAdbCannotBeReachedThenGivesUp() = runBlocking {
        // Debugging is off: every connection is refused in a few milliseconds.
        val rig = Rig(Try(5, false))

        assertFalse(rig.access.await(2_000))
        assertTrue("${rig.tries} tries", rig.tries in 5..8)
        assertTrue(rig.logs.last(), rig.logs.last().contains("cannot be reached"))
    }

    @Test
    fun picksUpWhenAdbComesUpWhileItIsStillTrying() = runBlocking {
        val rig = Rig(Try(5, false), Try(5, false), Try(80, true))

        assertTrue(rig.access.await(5_000))
        assertEquals(3, rig.tries)
    }

    @Test
    fun aPromptThatWasDeclinedOrIgnoredIsNotRetriedAtOnce() = runBlocking {
        // Sixty seconds waiting, then nothing: not allowed, and no second prompt straight away.
        val rig = Rig(Try(AdbAccess.ANSWER_WAIT_MS, false))

        assertFalse(rig.access.await(8_000))
        assertEquals(1, rig.tries)
        assertTrue(rig.logs.last(), rig.logs.last().contains("declined or not answered"))
    }

    @Test
    fun callersQueuedBehindADeclinedPromptDoNotPutItUpAgain() = runBlocking {
        val rig = Rig(Try(4_000, false))
        assertFalse(rig.access.await(8_000))

        // Two more callers a few seconds later: no new connection, so no new prompt.
        rig.clock += 5_000
        assertFalse(rig.access.await(8_000))
        assertFalse(rig.access.await(8_000))
        assertEquals(1, rig.tries)
    }

    @Test
    fun asksAgainOnceTheCooldownIsOver() = runBlocking {
        val rig = Rig(Try(4_000, false), Try(9_000, true))
        assertFalse(rig.access.await(8_000))

        rig.clock += AdbAccess.DECLINED_COOLDOWN_MS
        assertTrue(rig.access.await(8_000))
        assertEquals(2, rig.tries)
    }

    @Test
    fun checksAgainAfterAConnectionThatHadWorkedFails() = runBlocking {
        val rig = Rig(Try(100, true), Try(5, false))
        rig.access.await(1_000)

        rig.access.forget()

        // It looked again (and found ADB gone) instead of trusting the earlier yes.
        assertFalse(rig.access.await(500))
        assertTrue(rig.tries >= 2)
    }

    @Test
    fun concurrentCallersShareOneConnectionToTheCar() = runBlocking {
        val rig = Rig(Try(10_000, true))

        val answers = (1..4).map { async { rig.access.await(8_000) } }.map { it.await() }

        assertTrue(answers.all { it })
        assertEquals(1, rig.tries)
    }
}

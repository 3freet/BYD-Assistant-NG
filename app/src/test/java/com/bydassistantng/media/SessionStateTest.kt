package com.bydassistantng.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    private val sample = """
      package=com.spotify.music
      active=true
      state=PlaybackState {state=3, position=60710, buffered position=0, speed=1.0, updated=1, actions=3025844, active item id=1, error=null}
      metadata: size=18, description=Imagine - Remastered 2010, John Lennon, Imagine
    """.trimIndent()

    @Test
    fun readsStatePositionAndTitle() {
        val s = SessionState.parse(sample)
        assertEquals(3, s.state)
        assertEquals(60710L, s.position)
        assertEquals("Imagine - Remastered 2010, John Lennon, Imagine", s.title)
        assertTrue(s.isPlaying)
    }

    @Test
    fun anAppWithoutASessionReadsAsNothing() {
        val s = SessionState.parse("")
        assertNull(s.state)
        assertNull(s.title)
        assertFalse(s.isPlaying)
    }

    @Test
    fun somethingNewPlayingCountsAtOnceWhenNothingWasPlaying() {
        val before = SessionState(2, 59918, "Old")
        assertTrue(SessionState.startedSince(before, SessionState(3, 100, "New"), waitedLong = false))
        assertFalse(SessionState.startedSince(before, SessionState(2, 59918, "Old"), waitedLong = true))
    }

    @Test
    fun whileSomethingPlaysOnlyAChangeOrARestartCounts() {
        val before = SessionState(3, 90_000, "Old")
        assertFalse("the old track still playing is not the request", SessionState.startedSince(before, SessionState(3, 92_000, "Old"), waitedLong = false))
        assertTrue(SessionState.startedSince(before, SessionState(3, 500, "New"), waitedLong = false))
        assertTrue("the same song again restarts", SessionState.startedSince(before, SessionState(3, 700, "Old"), waitedLong = false))
        assertTrue("asking for what already plays is accepted after a wait", SessionState.startedSince(before, SessionState(3, 95_000, "Old"), waitedLong = true))
    }
}

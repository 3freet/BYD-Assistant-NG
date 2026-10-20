package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelGateTest {
    private val multiplier = LevelGate.multiplier(0)

    /** A chunk whose bytes are all [marker], so that real audio and silence can be told apart. */
    private fun chunk(marker: Int) = ByteArray(100) { marker.toByte() }

    private fun isSilence(chunk: ByteArray?) = chunk != null && chunk.all { it == 0.toByte() }

    /** A level's marker byte: different for the levels used here, never 0 (which is silence). */
    private fun marker(level: Int) = level / 100 + 1

    /** Feeds levels (each chunk carries its level's marker) and returns what came out, in order. */
    private fun LevelGate.feed(vararg runs: Pair<Int, Int>): List<ByteArray?> = runs.flatMap { (level, count) ->
        List(count) { process(chunk(marker(level)), level, multiplier) }
    }

    @Test
    fun theFirstChunksAreHeldBackForTheLookahead() {
        val gate = LevelGate()
        val out = gate.feed(300 to 5)
        assertEquals(3, out.take(3).count { it == null })
        assertNotNull(out[3])
    }

    @Test
    fun aSteadyBackgroundIsSentAsSilenceOnceItIsKnown() {
        val gate = LevelGate()
        val out = gate.feed(300 to 40).filterNotNull()
        // The first chunks, before the background is known, go through; after that the music alone is silenced.
        assertTrue(out.takeLast(20).all { isSilence(it) })
        assertTrue(gate.chunksSilenced > 20)
    }

    @Test
    fun theUsersVoiceClearlyAboveTheBackgroundGoesThroughWholeAndTheMusicAroundItDoesNot() {
        val gate = LevelGate()
        gate.feed(300 to 30)
        val out = gate.feed(3000 to 10, 300 to 30).filterNotNull()
        val real = out.count { !isSilence(it) }
        // The ten loud chunks, the lookahead before them and the hangover after them; the rest is silence.
        assertTrue("$real real", real in 10..10 + 3 + 6 + 1)
        assertTrue(isSilence(out.last()))
    }

    @Test
    fun theSoftStartOfAWordIsNotClipped() {
        val gate = LevelGate()
        gate.feed(300 to 30)
        // 200 is under the bar (300 x 1.7) but is the start of a word whose loud part follows at once.
        val out = gate.feed(200 to 2, 3000 to 6, 300 to 20).filterNotNull()
        // Both soft chunks come out as real audio, in front of the loud ones (the lookahead also lets the chunk before them by).
        val soft = out.filter { !isSilence(it) && it.first().toInt() == marker(200) }
        assertEquals(2, soft.size)
        val firstSoft = out.indexOfFirst { !isSilence(it) && it.first().toInt() == marker(200) }
        val firstLoud = out.indexOfFirst { !isSilence(it) && it.first().toInt() == marker(3000) }
        assertTrue("$firstSoft before $firstLoud", firstSoft in 0 until firstLoud)
    }

    @Test
    fun aVoiceNotClearlyLouderThanTheMusicIsCutAndTheBarMovesWithTheSensitivity() {
        fun silenced(step: Int): Int {
            val gate = LevelGate()
            val m = LevelGate.multiplier(step)
            repeat(30) { gate.process(chunk(31), 300, m) }
            repeat(20) { gate.process(chunk(61), 600, m) }
            return gate.chunksSilenced
        }
        // 600 over a background of 300 is twice it: through at the middle and the sensitive steps, cut at the firm one.
        assertTrue(silenced(0) < silenced(-3))
        assertTrue(silenced(3) <= silenced(0))
    }

    @Test
    fun theBarNeverFallsBelowTheFloorWhateverTheBackground() {
        val gate = LevelGate()
        gate.feed(10 to 30)
        assertEquals(LevelGate.MIN_BAR, gate.bar)
        val out = gate.feed(100 to 10, 200 to 10).filterNotNull()
        assertTrue("100 is under the floor", isSilence(out.first()))
        assertTrue("200 is above it", out.any { !isSilence(it) })
    }

    @Test
    fun aLongLoudStretchIsSentWholeAndNothingOfItIsGatedAway() {
        val gate = LevelGate()
        gate.feed(300 to 20)
        val out = gate.feed(2000 to 40).filterNotNull()
        assertTrue(out.takeLast(30).none { isSilence(it) })
    }

    @Test
    fun theUsersOwnVoiceNeverRaisesTheBackground() {
        val gate = LevelGate()
        gate.feed(300 to 30)
        val before = gate.backgroundLevel
        gate.feed(3000 to 40)
        assertEquals(before, gate.backgroundLevel)
    }

    @Test
    fun aNewTurnKeepsTheBackgroundAndANewConversationForgetsIt() {
        val gate = LevelGate()
        gate.feed(300 to 30)
        val known = gate.backgroundLevel
        gate.beginTurn()
        assertEquals(known, gate.backgroundLevel)
        assertEquals(0, gate.chunksSeen)
        gate.reset()
        assertEquals(0, gate.backgroundLevel)
        assertNull(gate.process(chunk(5), 50, multiplier)) // holding again
    }

    @Test
    fun theMultiplierRisesAsTheSensitivityFalls() {
        assertEquals(1.7, LevelGate.multiplier(0), 0.0)
        for (step in VoiceSensitivity.RANGE.first until VoiceSensitivity.RANGE.last) {
            assertTrue(LevelGate.multiplier(step) > LevelGate.multiplier(step + 1))
        }
    }
}

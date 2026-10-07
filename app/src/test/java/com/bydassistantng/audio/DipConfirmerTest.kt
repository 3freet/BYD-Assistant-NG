package com.bydassistantng.audio

import com.bydassistantng.audio.DipConfirmer.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class DipConfirmerTest {
    private fun DipConfirmer.feed(vararg levels: Int): List<Verdict> = levels.map { onChunk(it) }

    @Test
    fun continuingSpeechIsConfirmedAsSoonAsThreeChunksAreLoud() {
        val c = DipConfirmer().apply { begin() }
        assertEquals(listOf(Verdict.PENDING, Verdict.PENDING, Verdict.CONFIRMED), c.feed(2500, 3100, 2800))
    }

    @Test
    fun speechThatDipsBetweenWordsIsStillConfirmed() {
        val c = DipConfirmer().apply { begin() }
        assertEquals(Verdict.CONFIRMED, c.feed(2200, 300, 2400, 250, 2600).last())
    }

    @Test
    fun aBurstThatDiesAwayIsRejectedWhenTheWindowEnds() {
        val c = DipConfirmer().apply { begin() }
        // Two loud chunks of a motor starting, then quiet: not enough, and nothing more comes.
        val verdicts = c.feed(1700, 1500, 90, 60, 40, 30, 25, 20)
        assertEquals(List(7) { Verdict.PENDING } + Verdict.REJECTED, verdicts)
    }

    @Test
    fun theEchoAfterTheDipIsFarBelowTheConfirmLevel() {
        val c = DipConfirmer().apply { begin() }
        // Reply at 5000 leaking at 10% with the volume dipped to ~12%: about 60.
        assertEquals(Verdict.REJECTED, c.feed(*IntArray(8) { 60 }).last())
    }

    @Test
    fun beginStartsAFreshCount() {
        val c = DipConfirmer()
        c.begin(); c.feed(2000, 2000)
        c.begin()
        assertEquals(listOf(Verdict.PENDING, Verdict.PENDING, Verdict.CONFIRMED), c.feed(2000, 2000, 2000))
    }
}

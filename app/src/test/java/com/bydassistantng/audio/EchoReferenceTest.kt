package com.bydassistantng.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EchoReferenceTest {
    private fun chunk(value: Int) = ShortArray(4) { value.toShort() }

    @Test
    fun silenceUntilThatManyChunksHaveBeenPlayed() {
        val reference = EchoReference(4)
        assertArrayEquals(ShortArray(4), reference.back(1))
        reference.push(chunk(1))
        assertArrayEquals(chunk(1), reference.back(1))
        assertArrayEquals(ShortArray(4), reference.back(2))
        reference.push(chunk(2))
        assertArrayEquals(chunk(1), reference.back(2))
    }

    @Test
    fun backCountsFromTheChunkPlayedLast() {
        val reference = EchoReference(4)
        for (i in 1..5) reference.push(chunk(i))
        assertArrayEquals(chunk(5), reference.back(1))
        assertArrayEquals(chunk(4), reference.back(2))
        assertArrayEquals(chunk(1), reference.back(5))
    }

    @Test
    fun onlyTheLatestAreKeptAndOddLagsGiveSilence() {
        val reference = EchoReference(4)
        for (i in 1..20) reference.push(chunk(i))
        assertArrayEquals(chunk(20), reference.back(1))
        assertArrayEquals(chunk(13), reference.back(8))
        assertEquals(0, reference.back(9).sum())
        assertEquals(0, reference.back(0).sum())
        assertEquals(0, reference.back(-1).sum())
    }
}

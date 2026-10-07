package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEndpointerTest {

    /** Feeds [levels] (one per 50ms chunk) and returns the index of the chunk that ended speech. */
    private fun SpeechEndpointer.feed(vararg levels: Pair<Int, Int>): Int? {
        var index = 0
        for ((level, chunks) in levels) {
            repeat(chunks) {
                if (onChunk(level)) return index
                index++
            }
        }
        return null
    }

    @Test
    fun silenceNeverEndsATurn() {
        assertEquals(null, SpeechEndpointer().feed(100 to 400))
    }

    @Test
    fun speechFollowedByAPauseEnds() {
        val endpointer = SpeechEndpointer()
        // 0.5s of room noise, 1.5s of speech, then quiet.
        val ended = endpointer.feed(100 to 10, 3000 to 30, 100 to 40)
        assertTrue(endpointer.speechDetected)
        // 10 + 30 = 40 chunks of audio before the pause; 800ms = 16 quiet chunks must pass.
        assertEquals(40 + 15, ended)
    }

    @Test
    fun aShortDipBetweenWordsDoesNotEnd() {
        val endpointer = SpeechEndpointer()
        assertEquals(null, endpointer.feed(100 to 10, 3000 to 20, 100 to 10, 3000 to 20))
    }

    @Test
    fun firesOnlyOnce() {
        val endpointer = SpeechEndpointer()
        assertTrue(endpointer.feed(100 to 10, 3000 to 20, 100 to 30) != null)
        assertFalse(endpointer.onChunk(100))
    }

    @Test
    fun theListeningToneInTheWarmupIsIgnored() {
        val endpointer = SpeechEndpointer()
        // A loud blip in the first 400ms (the tone leaking in) must neither count as speech nor
        // poison the noise floor.
        assertEquals(null, endpointer.feed(6000 to 6, 100 to 100))
        assertFalse(endpointer.speechDetected)
        assertTrue(endpointer.threshold < 1000)
    }

    @Test
    fun aSingleBumpIsNotSpeech() {
        val endpointer = SpeechEndpointer()
        assertEquals(null, endpointer.feed(100 to 12, 4000 to 1, 100 to 60))
        assertFalse(endpointer.speechDetected)
    }

    @Test
    fun aNoisyCabinThatNeverQuietensNeverEnds() {
        // Constant loud noise: the floor rises to match, nothing ever counts as "then quiet".
        assertEquals(null, SpeechEndpointer().feed(2500 to 400))
    }

    @Test
    fun rmsOfPcm() {
        val silence = ByteArray(1600)
        assertEquals(0, pcm16Rms(silence))
        // A constant sample value of 1000 (little-endian 0xE8 0x03) has an RMS of exactly 1000.
        val constant = ByteArray(1600) { if (it % 2 == 0) 0xE8.toByte() else 0x03 }
        assertEquals(1000, pcm16Rms(constant))
    }

    @Test
    fun endsOverNoiseThatRoseAfterTheFloorWasLearnt() {
        // The A/C blower going to maximum: floor learnt at ~20, then a steady 600 that is "loud" by that floor.
        val endpointer = SpeechEndpointer()
        endpointer.beginMidSpeech(noiseFloor = 20.0)
        val ended = endpointer.feed(4000 to 20, 600 to 40)
        assertTrue("the pause should still be found over the louder noise", ended != null)
    }

    @Test
    fun aSoftWordInTheMiddleOfALoudSentenceDoesNotEndIt() {
        val endpointer = SpeechEndpointer()
        endpointer.beginMidSpeech(noiseFloor = 20.0)
        // Loud, then 400ms (8 chunks) at a softer 800: under the 800ms silence needed.
        assertEquals(null, endpointer.feed(6000 to 30, 800 to 8, 6000 to 20))
    }
}

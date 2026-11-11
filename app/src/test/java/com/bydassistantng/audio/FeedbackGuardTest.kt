package com.bydassistantng.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The squeal guard, checked with synthetic signals (time is counted in samples). */
class FeedbackGuardTest {
    private val rate = BROADCAST_SAMPLE_RATE
    private val chunk = rate / 100 // 10 ms

    private fun dbToLinear(db: Double) = Math.pow(10.0, db / 20.0)

    private fun tone(db: Double, hz: Double, chunks: Int, startChunk: Int = 0): List<ShortArray> = (0 until chunks).map { c ->
        ShortArray(chunk) { i ->
            val n = (startChunk + c) * chunk + i
            (sin(2 * PI * hz * n / rate) * dbToLinear(db) * 32767).toInt().toShort()
        }
    }

    private fun noise(db: Double, chunks: Int, seed: Long = 1): List<ShortArray> {
        val random = Random(seed)
        val amplitude = dbToLinear(db) * 32767
        return List(chunks) { ShortArray(chunk) { (random.nextGaussian() * amplitude).toInt().coerceIn(-32767, 32767).toShort() } }
    }

    /** A voiced sound: twenty harmonics of 120 Hz with aligned phases, which gives the sharp peaks speech has (a crest factor of about 4). */
    private fun voiced(db: Double, chunks: Int, envelopeHz: Double = 0.0, startChunk: Int = 0): List<ShortArray> = (0 until chunks).map { c ->
        ShortArray(chunk) { i ->
            val n = (startChunk + c) * chunk + i
            var v = 0.0
            for (h in 1..20) v += sin(2 * PI * 120.0 * h * n / rate + PI / 2) / h
            val envelope = if (envelopeHz == 0.0) 1.0 else 0.5 * (1 + sin(2 * PI * envelopeHz * n / rate))
            (v * envelope * dbToLinear(db) * 32767 / 2.0).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun run(guard: FeedbackGuard, chunks: List<ShortArray>) = chunks.map { guard.observe(it) }

    @Test
    fun aLoudSteadyToneIsASquealAfterASecondAndHeldSilentForAWhile() {
        val results = run(FeedbackGuard(), noise(-60.0, 10) + tone(-12.0, 1_000.0, 190, startChunk = 10))

        assertEquals("one squeal", 1, results.count { it.feedback })
        val at = results.indexOfFirst { it.feedback }
        assertTrue("after about a second of it (chunk $at)", at in 10 + 90..10 + 120)
        val mutedChunks = results.count { it.muted }
        assertTrue("held silent for about a second (was $mutedChunks chunks)", mutedChunks in 90..110)
    }

    @Test
    fun anotherSquealNeedsFreshEvidenceAfterTheHold() {
        val results = run(FeedbackGuard(), tone(-12.0, 1_000.0, 400))

        // The first squeal, the hold, then a second more of the tone before it is a squeal again.
        assertEquals(2, results.count { it.feedback })
    }

    @Test
    fun aVeryLoudShoutThatFlattensAndThenDiesAwayIsNotASqueal() {
        // What the microphone gave when it was shouted at (measured on the car): from nothing to a loud flat level for half a second, then a fall of
        // some 14 dB over the next half second. Flattened by the gain control and the peak limit it looks like a tone for the first part.
        val shout = tone(-7.0, 300.0, 50, startChunk = 10) + (0 until 60).flatMap { k -> tone(-7.0 - k * 0.25, 300.0, 1, startChunk = 60 + k) }
        val results = run(FeedbackGuard(), noise(-60.0, 10) + shout + noise(-60.0, 20, seed = 2))

        assertTrue("a shout is not feedback", results.none { it.feedback })
    }

    @Test
    fun speechThatRisesAndFallsIsNotFeedbackEvenWhenLoud() {
        val results = run(FeedbackGuard(), noise(-60.0, 10) + voiced(-8.0, 300, envelopeHz = 3.0, startChunk = 10))

        assertTrue(results.none { it.feedback })
    }

    @Test
    fun aSteadyToneThatIsQuietIsNotFeedback() {
        val results = run(FeedbackGuard(), noise(-60.0, 10) + tone(-45.0, 1_000.0, 300, startChunk = 10))

        assertTrue(results.none { it.feedback })
    }

    @Test
    fun aLoudToneThatWaversByMoreThanAFewDecibelsIsNotFeedback() {
        val wavering = (0 until 300).map { c ->
            ShortArray(chunk) { i ->
                val n = c * chunk + i
                val level = -12.0 - 8.0 * (0.5 + 0.5 * sin(2 * PI * 2.0 * n / rate))
                (sin(2 * PI * 1_000.0 * n / rate) * dbToLinear(level) * 32767).toInt().toShort()
            }
        }
        val results = run(FeedbackGuard(), wavering)

        assertTrue(results.none { it.feedback })
    }

    @Test
    fun anEmptyChunkIsHarmless() {
        val result = FeedbackGuard().observe(ShortArray(0))

        assertFalse(result.feedback)
        assertFalse(result.muted)
    }
}

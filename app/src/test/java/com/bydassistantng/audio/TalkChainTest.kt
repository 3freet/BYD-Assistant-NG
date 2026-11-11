package com.bydassistantng.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the live voice goes through, checked with synthetic signals at the levels the microphone's voice-processed source gives (16-bit units). */
class TalkChainTest {
    private val chunk = BROADCAST_CHUNK_SAMPLES
    private var phase = 0.0

    /** [chunks] chunks of a tone of [rms] (16-bit units); the phase goes on from one call to the next. */
    private fun tone(rms: Double, chunks: Int, hz: Double = 1_000.0): List<ShortArray> = List(chunks) {
        ShortArray(chunk) {
            phase += 2 * PI * hz / BROADCAST_SAMPLE_RATE
            (sin(phase) * rms * sqrt(2.0)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun noise(rms: Double, chunks: Int, seed: Long = 1): List<ShortArray> {
        val random = Random(seed)
        return List(chunks) { ShortArray(chunk) { (random.nextGaussian() * rms).toInt().coerceIn(-32768, 32767).toShort() } }
    }

    private fun rms(chunks: List<ShortArray>): Double {
        var sum = 0.0
        var n = 0
        for (c in chunks) for (v in c) {
            sum += v.toDouble() * v
            n++
        }
        return sqrt(sum / n)
    }

    private fun peak(chunks: List<ShortArray>) = chunks.maxOf { c -> c.maxOf { abs(it.toInt()) } }

    private fun run(chain: TalkChain, input: List<ShortArray>) = input.map { chain.process(it) }

    /** The chain with its thresholds fixed at the levels they were made for (a microphone that gives a hot voice). */
    private fun fixedChain() = TalkChain(null, TalkChain.Settings(adaptiveLevels = false))

    @Test
    fun aVoiceAtSpeechLevelComesOutAtTheSameLevel() {
        val out = run(fixedChain(), tone(6_000.0, 60))

        val level = rms(out.takeLast(20))
        assertEquals("unity: the filters leave the middle of the voice band alone", 6_000.0, level, 300.0)
    }

    @Test
    fun aPeakAboveTheLimitIsBroughtDownToIt() {
        val out = run(fixedChain(), tone(17_000.0, 60)) // a peak of 24000

        val peak = peak(out.takeLast(20))
        assertTrue("limited to 22000 (was $peak)", peak in 20_000..22_300)
    }

    @Test
    fun theGainIsAppliedAndNeverPastTheMost() {
        val chain = fixedChain()
        chain.gain = 2.0f
        val doubled = run(chain, tone(3_000.0, 60))
        assertEquals("twice the level", 6_000.0, rms(doubled.takeLast(20)), 400.0)

        val capped = fixedChain()
        capped.gain = 50.0f
        val most = run(capped, tone(1_000.0, 60))
        assertTrue("never more than 5 times (was ${rms(most.takeLast(20))})", rms(most.takeLast(20)) < 5_300.0)
    }

    @Test
    fun theVoiceIsFadedWhenTheMicrophoneHasBeenQuietForAWhileAndComesBackAtOnceWithTheVoice() {
        val chain = fixedChain()
        run(chain, tone(6_000.0, 20))
        val quietIn = tone(800.0, 100)
        val quietOut = run(chain, quietIn)

        assertEquals("still full right after the voice", rms(quietIn.take(15)), rms(quietOut.take(15)), rms(quietIn.take(15)) * 0.25)
        assertTrue("down to about an eighth at the end (in ${rms(quietIn.takeLast(10))}, out ${rms(quietOut.takeLast(10))})", rms(quietOut.takeLast(10)) < rms(quietIn.takeLast(10)) * 0.2)

        val back = run(chain, tone(6_000.0, 8))
        assertEquals("and back at full the moment the voice is", 6_000.0, rms(back.takeLast(3)), 600.0)
    }

    @Test
    fun aLevelBetweenQuietAndSpeechHoldsTheGainWhereItIs() {
        val out = run(fixedChain(), tone(3_000.0, 100))

        assertEquals(3_000.0, rms(out.takeLast(20)), 200.0)
    }

    @Test
    fun silentGivesSilence() {
        val chain = TalkChain(null)
        val out = chain.process(tone(6_000.0, 1).single(), silent = true)

        assertArrayEquals(ShortArray(chunk), out)
    }

    @Test
    fun aMicrophoneThatGivesAQuietVoiceStillGetsItThroughAtFullLevel() {
        // This unit's voice: loud syllables around 1500, pauses at the cabin's noise (60). With the thresholds as they are made for (5500 and
        // 1600) the fade would never open: the voice went out 18 dB down in the car.
        val fixed = fixedChain()
        val adaptive = TalkChain(null)
        val speech = tone(1_500.0, 120)
        val fixedOut = run(fixed, speech)
        val adaptiveOut = run(adaptive, speech)

        assertTrue("fixed: the fade closed on a quiet voice (${rms(fixedOut.takeLast(20))})", rms(fixedOut.takeLast(20)) < 400.0)
        assertEquals("adaptive: the voice goes out as it is", 1_500.0, rms(adaptiveOut.takeLast(20)), 150.0)

        val pause = noise(60.0, 120, seed = 3)
        val pauseOut = run(adaptive, pause)
        assertTrue("and the pause is faded as before (in ${rms(pause.takeLast(10))}, out ${rms(pauseOut.takeLast(10))})", rms(pauseOut.takeLast(10)) < rms(pause.takeLast(10)) * 0.2)

        val again = run(adaptive, tone(1_500.0, 6))
        assertEquals("and the voice is back at once", 1_500.0, rms(again.takeLast(2)), 250.0)
    }

    @Test
    fun aMicrophoneThatGivesAHotVoiceKeepsTheThresholdsAsTheyAre() {
        val chain = TalkChain(null)
        run(chain, tone(8_000.0, 300)) // the level they were made for: scale 1
        val between = run(chain, tone(3_000.0, 100))
        assertEquals("between quiet and speech the gain is held, as with the fixed numbers", 3_000.0, rms(between.takeLast(20)), 250.0)

        val quiet = run(chain, tone(1_000.0, 100))
        assertTrue("and 1000 is quiet (out ${rms(quiet.takeLast(10))})", rms(quiet.takeLast(10)) < 400.0)
    }

    @Test
    fun aClickDoesNotMoveWhatCountsAsSpeech() {
        val chain = TalkChain(null)
        run(chain, tone(1_500.0, 150)) // the voice level settles around 1500
        run(chain, tone(9_000.0, 1)) // one chunk of a click
        val after = run(chain, tone(1_500.0, 40))

        assertEquals("the voice still counts as speech after a click", 1_500.0, rms(after.takeLast(10)), 200.0)
    }

    @Test
    fun aSteadyMusicBackgroundIsFadedAwayWhereTheOrdinaryFadeLetsItThrough() {
        // A cabin's music at the microphone (RMS 250, a level at which the ordinary fade holds the gain and the music goes out whole).
        val ordinary = TalkChain(null)
        val withMusic = TalkChain(null).also { it.musicBackground = true }
        val background = noise(250.0, 400)
        val ordinaryOut = run(ordinary, background)
        val musicOut = run(withMusic, background)

        assertTrue("the ordinary fade lets it through (${rms(ordinaryOut.takeLast(50))})", rms(ordinaryOut.takeLast(50)) > 150.0)
        assertTrue("the music-aware one fades it to nearly nothing (${rms(musicOut.takeLast(50))})", rms(musicOut.takeLast(50)) < 25.0)
    }

    @Test
    fun aVoiceClearlyAboveTheMusicStillGoesOutAtFullLevel() {
        val chain = TalkChain(null).also { it.musicBackground = true }
        run(chain, noise(250.0, 300)) // the background is learned
        val voice = tone(2_500.0, 40)
        val out = run(chain, voice)

        assertEquals("at once and as it is", 2_500.0, rms(out.takeLast(20)), 300.0)

        val after = run(chain, noise(250.0, 200))
        assertTrue("and the music is faded again after it (${rms(after.takeLast(30))})", rms(after.takeLast(30)) < 40.0)
    }

    @Test
    fun theMusicBackgroundDoesNotCostTheVoiceItsOwnPausesWhenThereIsNoMusic() {
        // The same voice as before but without the music mode: untouched by it.
        val chain = TalkChain(null)
        val out = run(chain, tone(2_500.0, 60))

        assertEquals(2_500.0, rms(out.takeLast(20)), 300.0)
    }

    @Test
    fun theLevelsOfTheLastChunkAreKeptForTheMeter() {
        val chain = TalkChain(null)
        run(chain, tone(2_000.0, 5))

        assertEquals(2_000.0, chain.lastMicRms, 100.0)
        assertTrue("what was sent is about the same (${chain.lastSentRms})", chain.lastSentRms in 1_500.0..2_300.0)
        assertEquals("no canceller, no echo taken out", 0.0, chain.lastEchoRms, 0.001)
    }

    /**
     * The whole loop with a model of the car: what is played comes back at the microphone 250 ms later, inverted and [pathDb] down. A voice of
     * 6000 for 300 ms, then nothing; the cabin's own noise is 30. Returns the peak of what was sent out, every 10 ms.
     */
    private fun loop(pathDb: Double, seconds: Int): List<Int> {
        val chain = TalkChain(null)
        val path = Math.pow(10.0, pathDb / 20.0)
        val delayChunks = 25
        val random = Random(5)
        val voice = tone(6_000.0, 30)
        val played = ArrayList<ShortArray>()
        val peaks = ArrayList<Int>()
        for (k in 0 until seconds * 100) {
            val microphone = ShortArray(chunk) { i ->
                var v = random.nextGaussian() * 30
                if (k < 30) v += voice[k][i]
                if (k >= delayChunks) v -= played[k - delayChunks][i] * path
                v.toInt().coerceIn(-32768, 32767).toShort()
            }
            val out = chain.process(microphone)
            played += out
            peaks += out.maxOf { abs(it.toInt()) }
        }
        return peaks
    }

    @Test
    fun theEchoOfAVoiceDiesOutInsteadOfRinging() {
        // The car as measured (the sound the microphone hears is 24 dB under what is played), and a speaker five times as loud as that.
        for (pathDb in listOf(-24.0, -12.0, -3.0)) {
            val peaks = loop(pathDb, seconds = 8)

            assertTrue("path $pathDb dB: nothing left after four seconds (was ${peaks.drop(400).max()})", peaks.drop(400).max() < 1_000)
        }
    }
}

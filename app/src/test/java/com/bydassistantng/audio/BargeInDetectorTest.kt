package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInDetectorTest {

    /** Feeds (mic, reference, chunks) segments; returns the index of the chunk that triggered, if any. */
    private fun BargeInDetector.feed(vararg segments: Triple<Int, Int, Int>): Int? {
        var index = 0
        for ((mic, ref, chunks) in segments) {
            repeat(chunks) {
                if (onChunk(mic, ref)) return index
                index++
            }
        }
        return null
    }

    // Levels below are modelled on a DiLink head unit through VOICE_COMMUNICATION: ambient ~20–40, echo up to a
    // few hundred above that, and the user's voice in the thousands.

    @Test
    fun aFalseAlarmRaisesTheBarSoTheSameBurstDoesNotTriggerAgain() {
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20))
        detector.startReply()
        // Settle at 10% coupling, then a burst at ~30% (as recorded at 21:34:10: mic 1719 over reference 5426).
        val first = detector.feed(Triple(560, 5400, 30), Triple(1720, 5400, 20))
        assertTrue("the burst should trigger the first time", first != null)
        detector.noteFalseAlarm()
        assertEquals("the same burst must not trigger again", null, detector.feed(Triple(560, 5400, 10), Triple(1720, 5400, 20)))
    }

    @Test
    fun aFalseAlarmDoesNotMakeRealSpeechImpossible() {
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20))
        detector.startReply()
        detector.feed(Triple(560, 5400, 30), Triple(1720, 5400, 20))
        detector.noteFalseAlarm()
        // The user's voice, in the thousands, still gets through later in the same reply.
        assertTrue(detector.feed(Triple(560, 5400, 10), Triple(5000, 5400, 20)) != null)
    }

    @Test
    fun theAssistantsOwnEchoNeverTriggers() {
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20)) // learn the ambient level
        detector.startReply()
        // Reply at 3000 leaking into the mic at 5% (150 above ambient).
        assertEquals(null, detector.feed(Triple(180, 3000, 200)))
    }

    @Test
    fun theUserSpeakingOverTheReplyTriggers() {
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20))
        detector.startReply()
        val at = detector.feed(Triple(180, 3000, 30), Triple(2500, 3000, 20))
        assertTrue("should trigger", at != null)
        // Takes five loud chunks (250ms) to be sure, not fewer.
        assertTrue("triggered at $at", at!! in 34..36)
    }

    @Test
    fun speechThatDipsBetweenWordsStillTriggers() {
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20))
        detector.startReply()
        // Loud, dip, loud, dip...: never five in a row, but five within eight.
        val at = detector.feed(
            Triple(180, 3000, 30),
            Triple(2200, 3000, 2), Triple(300, 3000, 1), Triple(2400, 3000, 2), Triple(300, 3000, 1), Triple(2600, 3000, 2),
        )
        assertTrue("should trigger across dips", at != null)
    }

    @Test
    fun aLouderPassageOfTheReplyRaisesTheBarWithIt() {
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20))
        detector.startReply()
        // Quiet start (echo 90 above ambient), then the reply gets 3x louder and so does the echo.
        assertEquals(null, detector.feed(Triple(120, 1800, 20), Triple(300, 5400, 60)))
    }

    @Test
    fun speakingWhileNothingIsPlayingIsPlainSpeechDetection() {
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20))
        assertEquals(4, detector.feed(Triple(2000, 0, 20)))
    }

    @Test
    fun aSingleLoudBlipIsIgnored() {
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20))
        detector.startReply()
        assertEquals(null, detector.feed(Triple(180, 3000, 20), Triple(4000, 3000, 2), Triple(180, 3000, 30)))
    }

    @Test
    fun aQuietMurmurBelowTheAbsoluteMinimumIsIgnored() {
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20))
        detector.startReply()
        // The failure seen on the car: a faint ~800-peak sound cut a reply off and nothing was said.
        assertEquals(null, detector.feed(Triple(180, 3000, 20), Triple(420, 3000, 40)))
    }

    @Test
    fun ambientNoiseAloneNeverTriggers() {
        val detector = BargeInDetector()
        // A noisy cabin (350–500 measured with the AC on) and no speech.
        assertEquals(null, detector.feed(Triple(400, 0, 100)))
        detector.startReply()
        assertEquals(null, detector.feed(Triple(560, 3000, 100)))
    }

    @Test
    fun theUserTalkingFromTheVeryStartOfTheReplyIsMissedNotMisjudged() {
        // Safe failure: calibration absorbs the user's voice as "echo", so no trigger — but also no false cut-off.
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20))
        detector.startReply()
        assertEquals(null, detector.feed(Triple(2500, 3000, 12)))
    }

    @Test
    fun aWeakSoundBelowTheMinimumNeverCutsAReplyOff() {
        val detector = BargeInDetector()
        detector.feed(Triple(30, 0, 20))
        detector.startReply()
        // What the car showed: triggers at 550–790 produced junk transcripts. Sustained, but too weak to be the user.
        assertEquals(null, detector.feed(Triple(150, 3000, 20), Triple(790, 3000, 80)))
    }

    @Test
    fun echoThatGrowsThroughALongReplyDoesNotTrigger() {
        val detector = BargeInDetector()
        detector.feed(Triple(15, 0, 20))
        detector.startReply()
        // Calibrated while the mic's gain was still low (echo ~40), then the gain recovers and the same
        // playback comes back at ~700 — the case that was cutting replies off after a couple of seconds.
        assertEquals(null, detector.feed(Triple(55, 3000, 14), Triple(250, 3000, 20), Triple(480, 3000, 20), Triple(700, 3000, 60)))
    }

    @Test
    fun realSpeechMeasuredOnTheCarStillTriggers() {
        val detector = BargeInDetector()
        detector.feed(Triple(20, 0, 20))
        detector.startReply()
        // The weakest genuine barge-in logged (1447 over a 4136 reply) must still be heard.
        assertTrue(detector.feed(Triple(70, 4136, 14), Triple(1450, 4136, 20)) != null)
    }
}

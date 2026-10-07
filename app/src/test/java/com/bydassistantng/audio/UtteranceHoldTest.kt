package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class UtteranceHoldTest {
    private fun chunk(i: Int) = byteArrayOf((i shr 8).toByte(), i.toByte())
    private fun id(b: ByteArray) = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)

    @Test
    fun whenNotHoldingNothingIsKept() {
        val hold = UtteranceHold()
        assertFalse(hold.isHolding)
        assertFalse(hold.offer(chunk(1)))
        assertEquals(0, hold.release { })
    }

    @Test
    fun releaseHandsOverTheSeedThenWhatWasOfferedInOrder() {
        val hold = UtteranceHold()
        hold.begin(listOf(chunk(1), chunk(2)))
        assertTrue(hold.isHolding)
        assertTrue(hold.offer(chunk(3)))
        assertTrue(hold.offer(chunk(4)))
        val sent = ArrayList<Int>()
        assertEquals(4, hold.release { sent += id(it) })
        assertEquals(listOf(1, 2, 3, 4), sent)
    }

    @Test
    fun afterReleaseChunksGoLive() {
        val hold = UtteranceHold()
        hold.begin(emptyList())
        hold.release { }
        assertFalse(hold.isHolding)
        assertFalse(hold.offer(chunk(9)))
    }

    @Test
    fun releaseTwiceSendsNothingTheSecondTime() {
        val hold = UtteranceHold()
        hold.begin(listOf(chunk(1)))
        assertEquals(1, hold.release { })
        assertEquals(0, hold.release { })
    }

    @Test
    fun discardDropsEverythingAndSendsNothing() {
        val hold = UtteranceHold()
        hold.begin(listOf(chunk(1), chunk(2)))
        hold.discard()
        assertFalse(hold.isHolding)
        val sent = ArrayList<Int>()
        assertEquals(0, hold.release { sent += id(it) })
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aReleaseRacingTheMicThreadNeverLosesOrReordersAChunk() {
        repeat(50) {
            val hold = UtteranceHold()
            hold.begin(emptyList())
            val total = 5_000
            val sentWhileHeld = ArrayList<Int>()
            val sentLive = ArrayList<Int>() // written only by the producer thread
            val started = CountDownLatch(1)
            val done = AtomicBoolean(false)

            val producer = Thread {
                started.countDown()
                for (i in 0 until total) if (!hold.offer(chunk(i))) sentLive += i
                done.set(true)
            }
            producer.start()
            started.await()
            Thread.sleep(0, 200_000)
            hold.release { sentWhileHeld += id(it) }
            producer.join()

            // Everything handed over at release came first in time, then everything sent live: together they
            // must be exactly 0..total-1 in order.
            assertEquals((0 until total).toList(), sentWhileHeld + sentLive)
        }
    }
}

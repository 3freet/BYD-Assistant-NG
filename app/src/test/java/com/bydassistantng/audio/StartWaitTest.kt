package com.bydassistantng.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartWaitTest {
    @Test
    fun aHelperThatIsThereAnswersAtOnce() = runBlocking {
        val ready = CompletableDeferred(true)

        assertEquals(true, awaitHelperOrStop(ready, CompletableDeferred<Unit>()))
    }

    @Test
    fun noHelperIsAnAnswerToo() = runBlocking {
        val ready = CompletableDeferred(false)

        assertEquals(false, awaitHelperOrStop(ready, CompletableDeferred<Unit>()))
    }

    @Test
    fun waitsForTheHelperWhileNothingIsPressed() = runBlocking {
        val ready = CompletableDeferred<Boolean>()
        val waiting = async { awaitHelperOrStop(ready, CompletableDeferred<Unit>()) }
        yield()
        assertTrue("still waiting", waiting.isActive)

        ready.complete(true)

        assertEquals(true, waiting.await())
    }

    @Test
    fun aPressEndsTheWaitAndLeavesTheHelperAlone() = runBlocking {
        // The helper is stuck behind the car's prompt: the press must not be stuck with it, and must not break it off either.
        val ready = CompletableDeferred<Boolean>()
        val stop = CompletableDeferred<Unit>()
        val waiting = async { awaitHelperOrStop(ready, stop) }
        yield()
        assertTrue("still waiting", waiting.isActive)

        stop.complete(Unit)

        assertNull(waiting.await())
        assertTrue("the helper goes on in the background", ready.isActive)
        assertFalse(ready.isCancelled)
    }

    @Test
    fun aPressThatCameBeforeTheWaitEndsItAtOnce() = runBlocking {
        val stop = CompletableDeferred<Unit>().apply { complete(Unit) }

        assertNull(awaitHelperOrStop(CompletableDeferred<Boolean>(), stop))
    }

    @Test
    fun aPressWinsWhenBothAreOverTogether() = runBlocking {
        val stop = CompletableDeferred<Unit>().apply { complete(Unit) }

        assertNull(awaitHelperOrStop(CompletableDeferred(true), stop))
    }
}

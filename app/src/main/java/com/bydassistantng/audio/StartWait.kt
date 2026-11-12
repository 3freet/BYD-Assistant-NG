package com.bydassistantng.audio

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select

/**
 * Waits for the exterior speaker's helper to be there, or for a press that ends the start of a broadcast, whichever comes first: true or false
 * is whether there is a helper, null is a press (it wins when both are over at the same moment).
 *
 * It only waits. [ready] is not cancelled: what it does (a connection that waits for the answer to the car's "Allow USB debugging?" prompt is a
 * blocking call that cannot be broken off from outside) goes on in the background and ends in its own time, as the helper preparing itself does
 * when nobody waits for it. What a press has to be able to end is the *start*, which cannot be done by cancelling this.
 */
internal suspend fun awaitHelperOrStop(ready: Deferred<Boolean>, stop: Deferred<*>): Boolean? = select<Boolean?> {
    stop.onAwait { null }
    ready.onAwait { it }
}

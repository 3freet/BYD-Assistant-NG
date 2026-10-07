package com.bydassistantng.audio

/**
 * Keeps what the user says *locally* for a moment instead of sending it to the server.
 *
 * Needed after talking over the assistant while the server is still finishing the reply that was just
 * cut off: on a head unit, speech sent in that window was ignored or came back as a fragment (3 of 3
 * cases where the old reply took ≥1.8s to finish), while speech sent once the old turn had completed was
 * always heard in full. So the interruption is held here until the server is ready, then handed over in
 * one piece, in order.
 *
 * Called from two threads — the mic's capture thread ([offer]) and the server-event thread ([release]) —
 * so everything is synchronized, and [release] sends *inside* the lock: a chunk offered a moment later
 * therefore can't overtake the ones being handed over.
 */
class UtteranceHold {
    private val chunks = ArrayList<ByteArray>()
    private var holding = false

    val isHolding: Boolean @Synchronized get() = holding

    /** Starts holding, beginning with [seed] (the audio from just before the interruption was noticed). */
    @Synchronized
    fun begin(seed: List<ByteArray>) {
        chunks.clear()
        chunks.addAll(seed)
        holding = true
    }

    /** @return true if the chunk was kept; false means nothing is held and the caller should send it live. */
    @Synchronized
    fun offer(chunk: ByteArray): Boolean {
        if (!holding) return false
        chunks.add(chunk)
        return true
    }

    /** Stops holding and passes everything held, oldest first, to [send]. @return how many chunks. */
    @Synchronized
    fun release(send: (ByteArray) -> Unit): Int {
        if (!holding) return 0
        holding = false
        val count = chunks.size
        chunks.forEach(send)
        chunks.clear()
        return count
    }

    /** Throws away anything held and stops holding, without sending. */
    @Synchronized
    fun discard() {
        holding = false
        chunks.clear()
    }
}

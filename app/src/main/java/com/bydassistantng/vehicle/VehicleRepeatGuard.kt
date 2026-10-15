package com.bydassistantng.vehicle

/**
 * Stops the model from sending the same command again and again.
 *
 * The Live model sometimes does not take "accepted" for an answer and calls the same function repeatedly, without
 * speaking: 41 times in 19 s for a window that had no read-back, until the wheel button was pressed. The car should
 * get such a command once.
 *
 * Only the command sent *immediately before* counts, so "open, close, open" still goes through; a command
 * that is not the same command and value is never held back. A repeat is answered from here with [REPEAT_NOTE], and it
 * extends the time window, so a model that keeps calling stays answered. [reset] starts a new user turn: the user
 * asking again on purpose is never a repeat. A command that failed is not remembered, so a retry goes out.
 *
 * [clock] is milliseconds from any steady source (injected for tests).
 */
class VehicleRepeatGuard(
    private val windowMs: Long = WINDOW_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var lastKey: String? = null
    private var lastAtMs = 0L

    /** Runs [send] unless it repeats the command that was just accepted, and says what happened either way. */
    suspend fun dispatch(commandId: String, value: Int, send: suspend () -> VehicleDispatchResult): VehicleDispatchResult {
        val key = "$commandId=$value"
        if (isRepeat(key)) return VehicleDispatchResult.Success(CommandAnswers.REPEAT_NOTE)
        val result = send()
        remember(if (result is VehicleDispatchResult.Success) key else null)
        return result
    }

    /** A new thing was asked of the assistant: nothing before it is a repeat any more. */
    @Synchronized
    fun reset() {
        lastKey = null
    }

    @Synchronized
    private fun isRepeat(key: String): Boolean {
        val now = clock()
        if (key != lastKey || now - lastAtMs !in 0..windowMs) return false
        lastAtMs = now
        return true
    }

    @Synchronized
    private fun remember(key: String?) {
        lastKey = key
        lastAtMs = clock()
    }

    companion object {
        // The model's repeats come within a second or two of each other. Longer than that is a person asking again.
        const val WINDOW_MS = 15_000L
    }
}

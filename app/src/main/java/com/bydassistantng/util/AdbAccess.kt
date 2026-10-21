package com.bydassistantng.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gets this app past the car's "Allow USB debugging?" prompt, once, before anything else uses ADB.
 *
 * The first connection from a new install (the app makes its own ADB key) makes the car ask the user to allow it, and
 * the connection simply waits for the answer. A connection that gives up after a few seconds takes the prompt down with
 * it, and the next attempt puts a fresh one up, so a driver who has not looked at the screen yet never gets the time to
 * answer. Here one connection waits for the answer, for a long time, and everybody else who needs ADB waits behind it
 * instead of opening prompts of their own.
 *
 * [tryOnce] opens a connection that waits [ANSWER_WAIT_MS] for the answer and says whether it was allowed.
 */
internal class AdbAccess(
    private val tryOnce: suspend () -> Boolean,
    private val now: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val log: (String) -> Unit,
) {
    private val lock = Mutex()

    @Volatile private var allowed = false
    private var declinedAt = NEVER

    /** Something went wrong with a connection that had worked: look again before the next one. */
    fun forget() {
        allowed = false
    }

    /**
     * True when this app may use ADB. Waits for the prompt to be answered if it is up. When ADB cannot be reached at all
     * (debugging is switched off, so the connection is refused at once) it keeps trying for [patienceMs], then says no.
     * A prompt that was declined or ignored is not asked again for [DECLINED_COOLDOWN_MS], so the callers queued behind it
     * do not put it back up one after the other.
     */
    suspend fun await(patienceMs: Long): Boolean {
        if (allowed) return true
        return lock.withLock { askUntilAnswered(patienceMs) }
    }

    private suspend fun askUntilAnswered(patienceMs: Long): Boolean {
        if (allowed) return true
        val started = now()
        if (declinedAt != NEVER && started - declinedAt < DECLINED_COOLDOWN_MS) {
            log("ADB was not allowed a moment ago — not asking again yet")
            return false
        }
        log("Checking that ADB is allowed (the car may ask \"Allow USB debugging?\" — waiting up to ${ANSWER_WAIT_MS / 1000} s for the answer)")
        while (true) {
            val before = now()
            val ok = tryOnce()
            val took = now() - before
            if (ok) {
                allowed = true
                declinedAt = NEVER
                log("ADB is allowed" + if (took >= QUICK_MS) " (after ${took / 1000} s)" else "")
                return true
            }
            // A refused connection fails in a moment; one that took a while was waiting for an answer and did not get a yes.
            if (took >= QUICK_MS) {
                declinedAt = now()
                log("ADB was not allowed: the \"Allow USB debugging\" prompt was declined or not answered (waited ${took / 1000} s)")
                return false
            }
            if (now() - started + RETRY_MS > patienceMs) {
                log("ADB cannot be reached — is USB debugging switched on?")
                return false
            }
            pause(RETRY_MS)
        }
    }

    companion object {
        /** How long the connection that is waiting for the user's answer is kept open. */
        const val ANSWER_WAIT_MS = 60_000L

        /** A failure faster than this is a refused connection, not a prompt that was waiting. */
        const val QUICK_MS = 1_500L
        const val RETRY_MS = 300L
        const val DECLINED_COOLDOWN_MS = 30_000L
        private const val NEVER = Long.MIN_VALUE
    }
}

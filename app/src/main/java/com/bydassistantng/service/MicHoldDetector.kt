package com.bydassistantng.service

import com.bydassistantng.util.WheelKeys

/**
 * Tells a short press of the steering-wheel microphone button from one that is held, for the live broadcast (see [LiveBroadcast][com.bydassistantng.audio.LiveBroadcast]).
 *
 * The unit sends two key codes for the button ([WheelKeys.MIC_SHORT_PRESS] and [WheelKeys.MIC_LONG_PRESS]) and it was not known, when
 * this was written, whether the short one comes when the button goes down or when it comes up, or when the long one comes. So this
 * does not rely on either:
 *
 *  - a press that has been seen going down is **timed here**: held for [holdMs] it is a hold (reported by [onTimer], which the service
 *    calls at that moment), released before that it is a short press (reported when the button comes up);
 *  - the unit's own long-press code ([WheelKeys.MIC_LONG_PRESS]) counts as a hold too: at once when no press was seen going down
 *    (there is nothing to time), and at the [holdMs] mark when one was (so a unit whose long press comes sooner than that still has to
 *    be held the full time).
 *
 * A short press is reported when the button comes up, not when it goes down, which is the price of telling the two apart: the
 * service uses this only while the live broadcast is armed, so that the assistant is not started by a press that turns out to be a hold.
 *
 * Pure logic: time is passed in (milliseconds, any steady clock), nothing here reads a clock or touches Android.
 */
internal class MicHoldDetector(private val holdMs: Long = HOLD_MS) {
    enum class Outcome {
        /** Nothing to do yet (or ever, for this event). */
        NONE,

        /** A short press is over: do what a press does. */
        PRESS,

        /** The button has been held long enough: start the broadcast. */
        HOLD,
    }

    private var downSinceMs = -1L
    private var holdReported = false

    /** Whether a press is in progress (the button is down, or a hold was reported and the button has not come up). */
    val inProgress: Boolean get() = downSinceMs >= 0 || holdReported

    /** A key event of the microphone button ([keyCode] is either of its two codes). Auto-repeats ([repeatCount] > 0) change nothing. */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, nowMs: Long): Outcome {
        if (keyCode != WheelKeys.MIC_SHORT_PRESS && keyCode != WheelKeys.MIC_LONG_PRESS) return Outcome.NONE
        if (down) {
            if (repeatCount > 0) return Outcome.NONE
            if (keyCode == WheelKeys.MIC_LONG_PRESS) {
                if (holdReported) return Outcome.NONE
                // The unit says it is a long press. With a press seen going down, wait for the full time; without, there is nothing to time.
                if (downSinceMs < 0) {
                    holdReported = true
                    return Outcome.HOLD
                }
                return onTimer(nowMs)
            }
            if (downSinceMs < 0 && !holdReported) downSinceMs = nowMs
            return Outcome.NONE
        }
        // Up. The long code's up ends the press without a short press; so does the short code's up after a hold.
        val wasHold = holdReported
        val timed = downSinceMs >= 0
        if (keyCode == WheelKeys.MIC_LONG_PRESS) {
            if (!timed) reset()
            return Outcome.NONE
        }
        reset()
        return if (!wasHold && timed) Outcome.PRESS else Outcome.NONE
    }

    /** The service calls this when [holdMs] have passed since a press went down; reports the hold if the button is still down. */
    fun onTimer(nowMs: Long): Outcome {
        if (downSinceMs < 0 || holdReported) return Outcome.NONE
        if (nowMs - downSinceMs < holdMs) return Outcome.NONE
        holdReported = true
        downSinceMs = -1L
        return Outcome.HOLD
    }

    /** Forgets a press whose end was never seen (a lost key-up), after [STALE_MS], so that the next press starts clean. */
    fun expireStale(nowMs: Long) {
        if (downSinceMs >= 0 && nowMs - downSinceMs > STALE_MS) reset()
    }

    fun reset() {
        downSinceMs = -1L
        holdReported = false
    }

    companion object {
        /** How long the button is held for the broadcast. */
        const val HOLD_MS = 1_000L

        private const val STALE_MS = 15_000L
    }
}

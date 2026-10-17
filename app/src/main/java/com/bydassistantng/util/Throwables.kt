package com.bydassistantng.util

import java.util.Collections
import java.util.IdentityHashMap

/**
 * The whole stack trace, every frame of every cause. `printStackTrace` shortens a cause's trace to the frames it does not
 * share with the one above and ends it with "... 58 more", which reads as if the log had been cut off.
 */
fun Throwable.fullStackTrace(): String = buildString {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this@fullStackTrace
    var first = true
    while (current != null && seen.add(current)) {
        if (!first) append("Caused by: ")
        append(current.toString()).append('\n')
        for (frame in current.stackTrace) append("\tat ").append(frame).append('\n')
        for (suppressed in current.suppressed) append("\tSuppressed: ").append(suppressed.toString()).append('\n')
        current = current.cause
        first = false
    }
}

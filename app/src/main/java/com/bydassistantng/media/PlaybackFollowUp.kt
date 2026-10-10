package com.bydassistantng.media

import android.content.Context
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "PlaybackFollowUp"

/** A play request whose search is on screen and whose top result still has to be pressed. */
data class PendingPlayback(
    val packageName: String,
    val appLabel: String,
    val query: String,
    val kind: MediaKind,
    /** True when the search is already pressed and a page is open: only its play control remains. */
    val pressPlayOnPage: Boolean = false,
)

/**
 * The second half of "play X on Spotify", run after the conversation has ended.
 *
 * Reading the screen goes through the system's UI automation, which switches every accessibility service off
 * while it looks (the wheel-button service among them, and it is the service that hosts the conversation).
 * Done during the conversation it cuts the assistant off mid-sentence and cancels itself. So the tool only
 * launches the app and leaves a note here; the press happens once the assistant has said its short
 * confirmation and the conversation is over, on a scope that outlives it.
 */
object PlaybackFollowUp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var pending: PendingPlayback? = null
    private var running: Job? = null

    /** Called with the outcome when the press did not end in something playing (to say so with a tone). */
    @Volatile var onNotStarted: (() -> Unit)? = null

    /** Whether a conversation is going; the screen is never read then, since that would cut it off. */
    @Volatile var isConversationActive: () -> Boolean = { false }

    fun schedule(request: PendingPlayback) {
        synchronized(lock) { pending = request }
    }

    /**
     * Drops a press that has not started, and one that is waiting to. A person pressing the wheel button again is
     * more important than finishing a song that was asked for before: reading the screen would switch the button off.
     */
    fun cancel() {
        synchronized(lock) {
            pending = null
            running?.cancel()
            running = null
        }
    }

    /** Starts the press for a scheduled request, if there is one. Safe to call at any time. */
    fun startPending(context: Context) {
        val request = synchronized(lock) { pending.also { pending = null } } ?: return
        if (isConversationActive()) {
            AppLogger.log(TAG, "Not starting ${request.appLabel}: a conversation is going")
            return
        }
        val appContext = context.applicationContext
        synchronized(lock) {
            running?.cancel()
            running = scope.launch {
                val starter = UiPlayStarter(appContext)
                val outcome = if (request.pressPlayOnPage) starter.pressPlayOnPage(request.packageName) else starter.startFirstResult(request.packageName, request.kind)
                AppLogger.log(TAG, "${request.appLabel} \"${request.query}\" -> $outcome")
                if (outcome !is StartOutcome.Playing && outcome !is StartOutcome.Pressed && outcome !is StartOutcome.PageOpened) onNotStarted?.invoke()
            }
        }
    }

    /** For tests on the device: runs the scheduled press now and waits for its outcome. */
    suspend fun runPendingNow(context: Context): StartOutcome? {
        val request = synchronized(lock) { pending.also { pending = null } } ?: return null
        val starter = UiPlayStarter(context.applicationContext)
        return if (request.pressPlayOnPage) starter.pressPlayOnPage(request.packageName) else starter.startFirstResult(request.packageName, request.kind)
    }
}

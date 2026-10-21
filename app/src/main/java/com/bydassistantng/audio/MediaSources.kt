package com.bydassistantng.audio

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.bydassistantng.service.MediaAccessService
import com.bydassistantng.util.AdbHelper
import com.bydassistantng.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

private const val TAG = "MediaSources"

/**
 * Which apps are playing media right now, from the system's media sessions. The radio (like the car's other built-in
 * sources) is not an Android player, so the list of active players never has it; it does publish a media session, with a
 * playing state. The system hands the list of sessions, and tells when it changes, only to an app that is a notification
 * listener; the app gives itself that access once, over its loopback ADB connection (it reads no notification, see
 * [MediaAccessService]). Nothing is polled: the system calls back when a session starts, stops or changes.
 *
 * Only the name of the app and whether it is playing are kept, never what it plays.
 */
@Singleton
class MediaSources @Inject constructor(@ApplicationContext private val context: Context) {
    private val component = ComponentName(context, MediaAccessService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<MediaController, MediaController.Callback>()

    @Volatile private var started = false
    @Volatile private var playing: Set<String> = emptySet()

    /** Whether anything other than the assistant (and the phone) is playing media: a radio, a streaming app, Bluetooth music. */
    fun anyPlaying(): Boolean = playing.isNotEmpty()

    /** Whether the app with this package name is playing media now. */
    fun isPlaying(packageName: String): Boolean = packageName in playing

    /** The apps playing now, for the log. */
    fun playingPackages(): Set<String> = playing

    /**
     * Starts listening to the sessions (once). The system shows them only to an app with notification access, which is
     * granted once with `cmd notification allow_listener` over the loopback ADB connection, like the other permissions the
     * app gives itself (writing the setting directly was not enough on this unit). It stays granted across restarts and
     * updates. Call it off the main thread: it returns when it has started or given up.
     */
    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            if (subscribe()) return
            // Not allowed yet: give the access, then try again.
            if (!grantAccess()) {
                AppLogger.log(TAG, "Notification access is off and could not be switched on — the radio will not be seen as playing")
                return
            }
            repeat(ATTEMPTS) {
                if (subscribe()) return
                Thread.sleep(RETRY_MS)
            }
            AppLogger.log(TAG, "The system would not let the app see the media sessions even with the access given")
        }
    }

    /** Listens to the sessions and reads the ones there are; false when the system says the app may not. */
    private fun subscribe(): Boolean = try {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        update(manager.getActiveSessions(component))
        handler.post {
            runCatching { manager.addOnActiveSessionsChangedListener({ update(it.orEmpty()) }, component, handler) }
                .onFailure { AppLogger.log(TAG, "Could not follow the media sessions: $it") }
        }
        started = true
        AppLogger.log(TAG, "Listening to the media sessions" + playing.takeIf { it.isNotEmpty() }?.let { " (playing now: ${it.joinToString()})" }.orEmpty())
        true
    } catch (_: SecurityException) {
        false
    }

    private fun grantAccess(): Boolean {
        val ok = runBlocking { AdbHelper.shell(context, "cmd notification allow_listener ${component.flattenToString()}") }
        if (ok) AppLogger.log(TAG, "Notification access given to the app for the media sessions")
        return ok
    }

    private fun update(controllers: List<MediaController>) {
        synchronized(callbacks) {
            // Stop watching sessions that are gone, start watching new ones.
            callbacks.keys.filter { known -> controllers.none { it.sessionToken == known.sessionToken } }.forEach { gone ->
                callbacks.remove(gone)?.let { runCatching { gone.unregisterCallback(it) } }
            }
            controllers.filter { c -> callbacks.keys.none { it.sessionToken == c.sessionToken } }.forEach { added ->
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
                    override fun onSessionDestroyed() = recompute()
                }
                callbacks[added] = callback
                runCatching { added.registerCallback(callback, handler) }
            }
        }
        recompute()
    }

    private fun recompute() {
        val now = synchronized(callbacks) {
            playingPackages(callbacks.keys.map { it.packageName to it.playbackState?.state }, ignored = setOf(context.packageName) + IGNORED_PACKAGES)
        }
        if (now != playing) {
            playing = now
            AppLogger.log(TAG, if (now.isEmpty()) "No media is playing" else "Playing: ${now.joinToString()}")
        }
    }

    companion object {
        private const val ATTEMPTS = 6
        private const val RETRY_MS = 1_000L

        /** The phone's own session is not music. */
        private val IGNORED_PACKAGES = setOf("com.android.server.telecom")

        /** The apps whose session is in a playing state (playing, or buffering what it is about to play), [ignored] left out. */
        fun playingPackages(sessions: List<Pair<String, Int?>>, ignored: Set<String>): Set<String> =
            sessions.filter { (name, state) -> name !in ignored && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) }
                .map { it.first }.toSet()
    }
}

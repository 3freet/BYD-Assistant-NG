package com.bydassistantng.media

import android.content.Context
import com.bydassistantng.util.AdbHelper
import com.bydassistantng.util.AppLogger
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "UiPlayStarter"
private const val CONNECT_TIMEOUT_MS = 6_000L
private const val SOCKET_TIMEOUT_MS = 20_000
private const val SETTLE_DELAY_MS = 2_000L
private const val RESULTS_WAIT_MS = 14_000L
private const val LOOK_INTERVAL_MS = 700L
private const val CONFIRM_ATTEMPTS = 10
private const val SAME_TRACK_ACCEPT_ATTEMPTS = 4
private const val DUMP_PATH = "/data/local/tmp/byd_assistant_ui.xml"
private val PACKAGE_NAME = Regex("[A-Za-z0-9._]+")

/** What happened after an app was asked to play from a search. */
sealed interface StartOutcome {
    /** The first result was pressed and the app reports it is playing. */
    data class Playing(val label: String) : StartOutcome

    /** The first result was pressed, but playback was not confirmed in time. */
    data class Pressed(val label: String) : StartOutcome

    /** The app's results never showed up on screen. */
    data object NoResults : StartOutcome

    /** There is no rule for this app: its results are on screen, but nobody pressed anything. */
    data object NoRule : StartOutcome

    /** The ADB connection the screen is read and pressed through was not available. */
    data object NoAdb : StartOutcome
}

/** The app's media session as `dumpsys media_session` reports it. */
data class SessionState(val state: Int?, val position: Long?, val title: String?) {
    val isPlaying: Boolean get() = state == 3 || state == 6

    companion object {
        private val STATE = Regex("""PlaybackState \{state=(\d+), position=(\d+)""")

        /** [dump] is the part of the dumpsys output that follows the app's `package=` line. */
        fun parse(dump: String): SessionState {
            val match = STATE.find(dump)
            val title = Regex("""description=([^\n]*)""").find(dump)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
            return SessionState(match?.groupValues?.get(1)?.toIntOrNull(), match?.groupValues?.get(2)?.toLongOrNull(), title)
        }

        /**
         * Whether [after] shows the requested track started, given the session [before] the press. Something
         * that was already playing only counts once the track changed or restarted; something that was not
         * playing counts as soon as it plays.
         */
        fun startedSince(before: SessionState, after: SessionState, waitedLong: Boolean): Boolean {
            if (!after.isPlaying) return false
            if (!before.isPlaying) return true
            val changed = after.title != null && after.title != before.title
            val restarted = before.position != null && after.position != null && after.position < before.position
            return changed || restarted || waitedLong
        }
    }
}

/**
 * Presses the first search result in an app that stopped on its results. It reads the screen and sends one tap
 * through the same loopback ADB connection the app already uses (the ADB shell is allowed to, an ordinary app is
 * not). It only looks at the elements of the one app being asked to play, keeps nothing of the screen, and
 * presses a single point.
 *
 * Spotify's rows are found from the app's own view dump, which involves no accessibility, so that runs safely while a
 * conversation is going. The system's UI automation (used for YouTube) switches the accessibility services off while
 * it looks, and needs the screen to go idle, so that kind runs only after the conversation; see [PlaybackFollowUp].
 */
class UiPlayStarter(private val context: Context) {

    suspend fun startFirstResult(packageName: String, kind: MediaKind): StartOutcome = withContext(Dispatchers.IO) {
        if (!FirstResult.hasRule(packageName) || !PACKAGE_NAME.matches(packageName)) return@withContext StartOutcome.NoRule
        val adb = AdbHelper.connect(context, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS) ?: return@withContext StartOutcome.NoAdb
        try {
            val target = findTarget(adb, packageName, kind) ?: run {
                AppLogger.log(TAG, "No results of $packageName on screen")
                return@withContext StartOutcome.NoResults
            }
            val before = sessionState(adb, packageName)
            AppLogger.log(TAG, "Pressing the first result of $packageName: ${target.label.take(60)}")
            adb.shell("input tap ${target.x} ${target.y}")
            repeat(CONFIRM_ATTEMPTS) { attempt ->
                delay(LOOK_INTERVAL_MS)
                if (SessionState.startedSince(before, sessionState(adb, packageName), waitedLong = attempt >= SAME_TRACK_ACCEPT_ATTEMPTS)) {
                    return@withContext StartOutcome.Playing(target.label)
                }
            }
            StartOutcome.Pressed(target.label)
        } catch (e: IOException) {
            AppLogger.logError(TAG, "Lost the ADB connection while starting playback", e)
            StartOutcome.NoAdb
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Whatever goes wrong while looking at the screen must never take the assistant down with it.
            AppLogger.logError(TAG, "Could not start playback", e)
            StartOutcome.NoResults
        } finally {
            runCatching { adb.close() }
        }
    }

    /** Finds the point to press, polling until the results are laid out (up to [RESULTS_WAIT_MS]). */
    private suspend fun findTarget(adb: Dadb, packageName: String, kind: MediaKind): TapPoint? {
        if (FirstResult.needsUiAutomation(packageName)) {
            delay(SETTLE_DELAY_MS)
            return readWithUiAutomation(adb, packageName, kind)
        }
        var waited = 0L
        while (waited <= RESULTS_WAIT_MS) {
            val dump = adb.shell("dumpsys activity top").output
            FirstResult.spotifyFromViews(ViewHierarchy.parse(dump), kind)?.let { return it }
            delay(LOOK_INTERVAL_MS)
            waited += LOOK_INTERVAL_MS
        }
        return null
    }

    /** One look at the screen through UI automation (the one moment accessibility is off), then the point to press. */
    private fun readWithUiAutomation(adb: Dadb, packageName: String, kind: MediaKind): TapPoint? {
        repeat(3) {
            val xml = adb.shell("uiautomator dump $DUMP_PATH >/dev/null 2>&1; cat $DUMP_PATH; rm -f $DUMP_PATH").output
            if (xml.contains("<hierarchy")) {
                FirstResult.forPackage(packageName, ScreenDump.parse(xml), kind)?.let { return it }
            }
        }
        return null
    }

    private fun sessionState(adb: Dadb, packageName: String): SessionState {
        val dump = adb.shell("dumpsys media_session | grep -a -A30 'package=$packageName' | head -n 32").output
        return SessionState.parse(dump)
    }
}

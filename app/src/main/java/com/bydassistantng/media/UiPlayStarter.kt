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
// After a press that opens a page: the second step only needs the page to have drawn.
private const val PAGE_SETTLE_DELAY_MS = 700L
private const val RESULTS_WAIT_MS = 14_000L
private const val LOOK_INTERVAL_MS = 700L
private const val CONFIRM_ATTEMPTS = 10
// A song starts within a second or two; a press that has not changed the track by then opened a page instead.
private const val PAGE_CONFIRM_ATTEMPTS = 3
private const val SAME_TRACK_ACCEPT_ATTEMPTS = 4
private const val PAGE_OPEN_WAIT_MS = 6_000L
// A page that just opened loads over the network: how long to give it, and how many looks for its play control.
private const val PAGE_LOAD_DELAY_MS = 1_500L
private const val PAGE_LOOKS = 3
// Looks at the library list for a named playlist: the first, and one more after each scroll.
private const val LIBRARY_LOOKS = 3
private const val SCROLL_DURATION_MS = 400
// Looks at a results page that may still be on its way: how many, and how far apart.
private const val RESULT_LOOKS = 4
private const val RESULT_LOOK_INTERVAL_MS = 1_500L
private const val QUICK_PRESSES = 9
private const val QUICK_PRESS_INTERVAL_MS = 1_300L
private const val KNOWN_SCREEN_WIDTH = 2560
private const val KNOWN_SCREEN_HEIGHT = 1440
private const val KNOWN_PLAY_X = 1864
private const val KNOWN_PLAY_Y = 950
private const val DUMP_PATH = "/data/local/tmp/byd_assistant_ui.xml"
private val PACKAGE_NAME = Regex("[A-Za-z0-9._]+")
// Spotify's play/pause control, found in its own view dump: the collapsed bar's, and the full now-playing view's in
// older versions. Other apps are paused with the media key: YouTube's player hides its controls a few seconds after
// a touch, and a tap on hidden controls only brings them back.
private val PLAY_PAUSE_IDS = mapOf(
    FirstResult.SPOTIFY to listOf("id/nowplaying_elements_playpause_button", "id/play_pause_button"),
)
private const val PAUSE_CONFIRM_ATTEMPTS = 5
private const val PAUSE_CONFIRM_INTERVAL_MS = 400L

/** What happened after an app was asked to play from a search. */
sealed interface StartOutcome {
    /** The first result was pressed and the app reports it is playing. */
    data class Playing(val label: String) : StartOutcome

    /** The first result was pressed, but playback was not confirmed in time. */
    data class Pressed(val label: String) : StartOutcome

    /** The first result was pressed and opened a page (a playlist, album or artist) whose play control is still to be pressed. */
    data class PageOpened(val label: String) : StartOutcome

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

    /** [query] is what the results are for: an app that shows an older page must not have its first result pressed. */
    suspend fun startFirstResult(packageName: String, kind: MediaKind, query: String = ""): StartOutcome = withContext(Dispatchers.IO) {
        if (!FirstResult.hasRule(packageName) || !PACKAGE_NAME.matches(packageName)) return@withContext StartOutcome.NoRule
        val adb = AdbHelper.connect(context, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS) ?: return@withContext StartOutcome.NoAdb
        try {
            val target = findTarget(adb, packageName, kind, query) ?: run {
                AppLogger.log(TAG, "No results of $packageName on screen")
                return@withContext StartOutcome.NoResults
            }
            val before = sessionState(adb, packageName)
            AppLogger.log(TAG, "Pressing the first result of $packageName: ${target.label.take(60)}")
            adb.shell("input tap ${target.x} ${target.y}")
            // A song asked for again may already be the one playing, so it counts after a wait. A playlist, album or
            // artist opens its page instead, and the old track simply carrying on is not success.
            val opensPage = packageName == FirstResult.SPOTIFY && kind != MediaKind.SONG
            repeat(if (opensPage) PAGE_CONFIRM_ATTEMPTS else CONFIRM_ATTEMPTS) { attempt ->
                delay(LOOK_INTERVAL_MS)
                val waitedLong = !opensPage && attempt >= SAME_TRACK_ACCEPT_ATTEMPTS
                if (SessionState.startedSince(before, sessionState(adb, packageName), waitedLong)) {
                    return@withContext StartOutcome.Playing(target.label)
                }
            }
            if (opensPage) {
                // Quick path for a screen whose layout is known; anything else waits for the labelled press.
                if (pressKnownPlayButton(adb, packageName)) return@withContext StartOutcome.Playing(target.label)
                StartOutcome.PageOpened(target.label)
            } else {
                StartOutcome.Pressed(target.label)
            }
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

    /**
     * The second step for a page that opened: press its play control. Reads the screen through UI automation, which
     * needs the screen idle, so anything still playing is paused first (from the app's own view dump).
     */
    suspend fun pressPlayOnPage(packageName: String): StartOutcome = withContext(Dispatchers.IO) {
        if (!PACKAGE_NAME.matches(packageName)) return@withContext StartOutcome.NoRule
        val adb = AdbHelper.connect(context, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS) ?: return@withContext StartOutcome.NoAdb
        try {
            pressPlay(adb, packageName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.logError(TAG, "Could not press play on the opened page", e)
            StartOutcome.NoResults
        } finally {
            runCatching { adb.close() }
        }
    }

    /**
     * "Play music from my playlists": the library page is open (the tool sent its link). Opens the playlist that
     * [name] names, or the most recent one when no name was said, and presses play on the page that opens.
     */
    suspend fun playFromPlaylists(packageName: String, name: String): StartOutcome = withContext(Dispatchers.IO) {
        if (!PACKAGE_NAME.matches(packageName)) return@withContext StartOutcome.NoRule
        val adb = AdbHelper.connect(context, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS) ?: return@withContext StartOutcome.NoAdb
        try {
            // Anything playing keeps the screen busy, and reading it needs the screen idle.
            val pausedByUs = sessionState(adb, packageName).isPlaying
            if (pausedByUs) pausePlayback(adb, packageName)
            delay(PAGE_SETTLE_DELAY_MS)
            val row = findLibraryRow(adb, packageName, name) ?: run {
                AppLogger.log(TAG, "No playlist${name.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()} on the library page of $packageName")
                // Nothing new to play: leave things as they were.
                if (pausedByUs) resumePlayback(adb, packageName)
                return@withContext StartOutcome.NoResults
            }
            AppLogger.log(TAG, "Opening the playlist \"${row.label.take(60)}\" of $packageName")
            adb.shell("input tap ${row.x} ${row.y}")
            delay(PAGE_LOAD_DELAY_MS)
            when (val outcome = pressPlay(adb, packageName)) {
                is StartOutcome.Playing -> StartOutcome.Playing(row.label)
                is StartOutcome.Pressed -> StartOutcome.Pressed(row.label)
                else -> outcome
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.logError(TAG, "Could not start a playlist", e)
            StartOutcome.NoResults
        } finally {
            runCatching { adb.close() }
        }
    }

    /** The row to open on the library page: looks, and scrolls on when a named playlist is not among the rows shown. */
    private suspend fun findLibraryRow(adb: Dadb, packageName: String, name: String): TapPoint? {
        repeat(LIBRARY_LOOKS) {
            val nodes = ScreenDump.parse(dumpScreen(adb))
            val library = libraryOf(packageName)
            library.target(nodes, packageName, name)?.let { return it }
            val rows = library.rows(nodes, packageName)
            if (name.isNotBlank() && rows.size > 1) {
                // The list is there but the name is not: move it up by about what is shown, and look again.
                val x = (rows.first().left + rows.first().right) / 2
                adb.shell("input swipe $x ${rows.last().let { (it.top + it.bottom) / 2 }} $x ${rows.first().let { (it.top + it.bottom) / 2 }} $SCROLL_DURATION_MS")
            }
            delay(LOOK_INTERVAL_MS)
        }
        return null
    }

    /** How an app lists the user's playlists on its library page. */
    private class Library(
        val rows: (List<ScreenNode>, String) -> List<LibraryRow>,
        val target: (List<ScreenNode>, String, String) -> TapPoint?,
    )

    private fun libraryOf(packageName: String): Library =
        if (packageName == FirstResult.YOUTUBE) {
            Library(YouTubeLibrary::rows, YouTubeLibrary::target)
        } else {
            Library(SpotifyLibrary::rows, SpotifyLibrary::target)
        }

    /**
     * Presses the play control of the open page, once it has drawn (a few looks: it loads over the network), and
     * waits for the app to report it playing. A second press follows only when nothing plays after the first.
     */
    private suspend fun pressPlay(adb: Dadb, packageName: String): StartOutcome {
        val before = sessionState(adb, packageName)
        val pausedByUs = before.isPlaying
        if (pausedByUs) pausePlayback(adb, packageName)
        delay(PAGE_SETTLE_DELAY_MS)
        // One look is one go of the system's UI automation: it switches the wheel button off while it runs.
        var button: TapPoint? = null
        for (look in 0 until PAGE_LOOKS) {
            val xml = dumpScreen(adb)
            button = if (xml.contains("<hierarchy")) FirstResult.playButtonOnPage(ScreenDump.parse(xml), packageName) else null
            if (button != null) break
            delay(PAGE_LOAD_DELAY_MS)
        }
        val target = button ?: run {
            if (pausedByUs) resumePlayback(adb, packageName)
            return StartOutcome.NoResults
        }
        repeat(2) { press ->
            AppLogger.log(TAG, "Pressing play on the opened page of $packageName (${target.label})")
            adb.shell("input tap ${target.x} ${target.y}")
            repeat(CONFIRM_ATTEMPTS) { attempt ->
                delay(LOOK_INTERVAL_MS)
                if (SessionState.startedSince(before.copy(state = 2), sessionState(adb, packageName), waitedLong = attempt >= SAME_TRACK_ACCEPT_ATTEMPTS)) {
                    return StartOutcome.Playing(target.label)
                }
            }
            // A page still filling in ignores the press. Press again only when nothing is playing, or it would pause.
            if (press == 0 && sessionState(adb, packageName).isPlaying) return StartOutcome.Playing(target.label)
        }
        return StartOutcome.Pressed(target.label)
    }

    /**
     * On the head unit this was built on, Spotify's playlist, album and artist pages put the green play control at
     * the same place, so it can be pressed without reading the page through UI automation (which needs accessibility
     * switched off and the screen idle, and takes seconds). Only used when the page is verifiably open and the
     * screen is the size the position was measured on; success is judged by the track actually changing.
     */
    private suspend fun pressKnownPlayButton(adb: Dadb, packageName: String): Boolean {
        if (packageName != FirstResult.SPOTIFY) return false
        val size = adb.shell("wm size").output
        if (!size.contains("${KNOWN_SCREEN_WIDTH}x$KNOWN_SCREEN_HEIGHT")) {
            AppLogger.log(TAG, "Quick press skipped: screen is ${size.trim().take(60)}")
            return false
        }
        // The page appears about three seconds after the press, longer on a slow connection.
        var open = false
        var waited = 0L
        while (!open && waited <= PAGE_OPEN_WAIT_MS) {
            open = FirstResult.spotifyResultsLeft(ViewHierarchy.parse(adb.shell("dumpsys activity top").output))
            if (!open) {
                delay(LOOK_INTERVAL_MS)
                waited += LOOK_INTERVAL_MS
            }
        }
        if (!open) {
            AppLogger.log(TAG, "Quick press skipped: the page was not open")
            return false
        }
        val before = sessionState(adb, packageName)
        // The page loads over the network and looks the same to the view dump before and after, so press until it
        // takes: a press on the spinner does nothing, and the first press after it has loaded starts the playlist.
        repeat(QUICK_PRESSES) { attempt ->
            adb.shell("input tap $KNOWN_PLAY_X $KNOWN_PLAY_Y")
            delay(QUICK_PRESS_INTERVAL_MS)
            val after = sessionState(adb, packageName)
            AppLogger.log(TAG, "Quick press ${attempt + 1}: ${after.state} ${after.title?.take(30)}")
            if (SessionState.startedSince(before, after, waitedLong = false)) return true
        }
        return false
    }

    /**
     * Puts back what [pausePlayback] stopped, for a request that ended up with nothing new to play: the app's own
     * control again, or failing that the media key.
     */
    private fun resumePlayback(adb: Dadb, packageName: String) {
        val button = playPauseButton(adb, packageName)
        if (button != null) {
            AppLogger.log(TAG, "Resuming $packageName from its ${button.resourceId.substringAfterLast('/')}")
            adb.shell("input tap ${(button.left + button.right) / 2} ${button.centerY}")
        } else {
            AppLogger.log(TAG, "Resuming $packageName with the media key (no control of its own on screen)")
            adb.shell("cmd media_session dispatch play")
        }
    }

    /**
     * Pauses what [packageName] is playing so the screen can go idle (a playing video keeps it busy for ever, and a
     * busy screen cannot be read). It presses the app's own control, found in its view dump, and only then falls
     * back to the media key, which goes to whichever app last held the media buttons and so may reach another one.
     * Returns whether the app reports it paused.
     */
    private suspend fun pausePlayback(adb: Dadb, packageName: String): Boolean {
        val button = playPauseButton(adb, packageName)
        if (button != null) {
            AppLogger.log(TAG, "Pausing $packageName from its ${button.resourceId.substringAfterLast('/')}")
            adb.shell("input tap ${(button.left + button.right) / 2} ${button.centerY}")
        } else {
            AppLogger.log(TAG, "Pausing $packageName with the media key (no control of its own on screen)")
            adb.shell("cmd media_session dispatch pause")
        }
        repeat(PAUSE_CONFIRM_ATTEMPTS) {
            delay(PAUSE_CONFIRM_INTERVAL_MS)
            if (!sessionState(adb, packageName).isPlaying) return true
        }
        return false
    }

    /** The visible play/pause control of [packageName] in its view dump, if it shows one. */
    private fun playPauseButton(adb: Dadb, packageName: String): ViewNode? {
        val ids = PLAY_PAUSE_IDS[packageName] ?: return null
        val nodes = ViewHierarchy.parse(adb.shell("dumpsys activity top").output)
        return ids.firstNotNullOfOrNull { id ->
            nodes.firstOrNull { it.activityPackage == packageName && it.resourceId.endsWith(id) && it.width > 0 && it.height > 0 }
        }
    }

    /** Finds the point to press, polling until the results are laid out (up to [RESULTS_WAIT_MS]). */
    private suspend fun findTarget(adb: Dadb, packageName: String, kind: MediaKind, query: String): TapPoint? {
        if (FirstResult.needsUiAutomation(packageName)) {
            delay(SETTLE_DELAY_MS)
            return readWithUiAutomation(adb, packageName, kind, query)
        }
        var waited = 0L
        var previous: TapPoint? = null
        while (waited <= RESULTS_WAIT_MS) {
            val nodes = ViewHierarchy.parse(adb.shell("dumpsys activity top").output)
            val point = FirstResult.spotifyFromViews(nodes, kind)
            // Press only once the list has filled in and the target has stayed put for two looks in a row: the
            // top card is drawn late, and a press on a list that is still moving lands on nothing.
            val settled = point != null && FirstResult.spotifyResultEntries(nodes).size >= FirstResult.SETTLED_ENTRIES
            if (settled && point == previous) return point
            previous = if (settled) point else null
            delay(LOOK_INTERVAL_MS)
            waited += LOOK_INTERVAL_MS
        }
        return previous
    }

    /**
     * The point to press, through UI automation (the one thing here that switches accessibility off, each look for
     * a few seconds). A video still playing keeps the screen busy, and a busy screen cannot be read, so it is
     * paused first (and put back if nothing new is found). A few looks, since the page for the new search may still
     * be on its way: only results that are for [query] count.
     */
    private suspend fun readWithUiAutomation(adb: Dadb, packageName: String, kind: MediaKind, query: String): TapPoint? {
        val pausedByUs = sessionState(adb, packageName).isPlaying
        repeat(RESULT_LOOKS) {
            // Paused before every look: the first press may have been too early (the player was still being
            // minimized), and a video that plays on makes the look fail.
            if (sessionState(adb, packageName).isPlaying) pausePlayback(adb, packageName)
            val xml = dumpScreen(adb)
            if (xml.contains("<hierarchy")) {
                FirstResult.forPackage(packageName, ScreenDump.parse(xml), kind, query)?.let { return it }
            }
            delay(RESULT_LOOK_INTERVAL_MS)
        }
        if (pausedByUs) resumePlayback(adb, packageName)
        return null
    }

    /** The screen as the system's UI automation reports it (empty or not a dump when the screen would not go idle). */
    private fun dumpScreen(adb: Dadb): String =
        adb.shell("uiautomator dump $DUMP_PATH >/dev/null 2>&1; cat $DUMP_PATH; rm -f $DUMP_PATH").output

    private fun sessionState(adb: Dadb, packageName: String): SessionState {
        val dump = adb.shell("dumpsys media_session | grep -a -A30 'package=$packageName' | head -n 32").output
        return SessionState.parse(dump)
    }
}

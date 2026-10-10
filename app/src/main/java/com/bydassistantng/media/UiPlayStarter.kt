package com.bydassistantng.media

import android.content.Context
import com.bydassistantng.util.AdbHelper
import com.bydassistantng.util.AppLogger
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "UiPlayStarter"
private const val CONNECT_TIMEOUT_MS = 6_000L
private const val SOCKET_TIMEOUT_MS = 20_000
private const val FIRST_LOOK_DELAY_MS = 1_500L
private const val RESULTS_WAIT_MS = 12_000L
private const val LOOK_INTERVAL_MS = 700L
private const val CONFIRM_ATTEMPTS = 8
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

/**
 * Presses the first search result in an app that stopped on its results. It reads the screen and sends one tap
 * through the same loopback ADB connection the app already uses (the ADB shell is allowed to, an ordinary app is
 * not). It only looks at the elements of the one app being asked to play, keeps nothing of the screen, and
 * presses a single point.
 */
class UiPlayStarter(private val context: Context) {

    suspend fun startFirstResult(packageName: String, kind: MediaKind): StartOutcome = withContext(Dispatchers.IO) {
        if (!FirstResult.hasRule(packageName) || !PACKAGE_NAME.matches(packageName)) return@withContext StartOutcome.NoRule
        val adb = AdbHelper.connect(context, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS) ?: return@withContext StartOutcome.NoAdb
        try {
            delay(FIRST_LOOK_DELAY_MS)
            var waited = 0L
            var target: TapPoint? = null
            while (target == null && waited <= RESULTS_WAIT_MS) {
                target = screen(adb)?.let { FirstResult.forPackage(packageName, ScreenDump.parse(it), kind) }
                if (target == null) {
                    delay(LOOK_INTERVAL_MS)
                    waited += LOOK_INTERVAL_MS
                }
            }
            if (target == null) {
                AppLogger.log(TAG, "No results of $packageName on screen after ${waited}ms")
                return@withContext StartOutcome.NoResults
            }
            AppLogger.log(TAG, "Pressing the first result of $packageName: ${target.label.take(60)}")
            adb.shell("input tap ${target.x} ${target.y}")
            repeat(CONFIRM_ATTEMPTS) {
                delay(LOOK_INTERVAL_MS)
                if (isPlaying(adb, packageName)) return@withContext StartOutcome.Playing(target.label)
            }
            StartOutcome.Pressed(target.label)
        } catch (e: IOException) {
            AppLogger.logError(TAG, "Lost the ADB connection while starting playback", e)
            StartOutcome.NoAdb
        } finally {
            runCatching { adb.close() }
        }
    }

    /** One look at the screen, or null when the dump failed (the screen was busy, for example). */
    private fun screen(adb: Dadb): String? {
        val output = adb.shell("uiautomator dump $DUMP_PATH >/dev/null 2>&1; cat $DUMP_PATH; rm -f $DUMP_PATH").output
        return output.takeIf { it.contains("<hierarchy") }
    }

    private fun isPlaying(adb: Dadb, packageName: String): Boolean {
        val line = adb.shell(
            "dumpsys media_session | grep -a -A8 'package=$packageName' | grep -a -m1 -oE 'PlaybackState \\{state=[0-9]+'",
        ).output
        val state = Regex("""state=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
        return state == PLAYING || state == BUFFERING
    }

    private companion object {
        const val PLAYING = 3
        const val BUFFERING = 6
    }
}

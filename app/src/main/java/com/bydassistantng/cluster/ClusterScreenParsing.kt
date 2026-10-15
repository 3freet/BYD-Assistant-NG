package com.bydassistantng.cluster

import com.bydassistantng.passenger.PassengerScreenParsing

/** A display as the display manager names it. */
internal data class NamedDisplay(val id: Int, val name: String)

/**
 * What the user chose under Advanced settings for the cluster screen: [enabled] says the feature is on at all, and
 * [keepMapsDisabled] whether BYD Maps is disabled again once the app has left the cluster and the cluster has its
 * gauges back (off, the default: it is left enabled).
 */
data class ClusterOptions(val enabled: Boolean = false, val keepMapsDisabled: Boolean = false) {
    companion object {
        val OFF = ClusterOptions()
    }
}

/**
 * The checks and command lines of the cluster screen, as plain text handling so they run in unit tests.
 *
 * How an app gets onto the car's instrument cluster (found on a Leopard 5, DiLink 5.1; see
 * docs/byd-internals/cluster-screen.md): the cluster shows what is drawn on the display the car publishes under a
 * name that starts with `fission_bg_` once the instrument "map type" is 4 (it is 1 normally), and it does so only
 * while BYD's own navigation app (`com.byd.naviauto`) is enabled and has started its meter activity there.
 */
internal object ClusterScreenParsing {
    /** BYD's navigation app. The cluster only takes another app's picture while this is enabled and has run. */
    const val MAPS_PACKAGE = "com.byd.naviauto"

    /** Its cluster activity: starting it on the cluster display is what the car expects of a map app. */
    const val METER_COMPONENT = "$MAPS_PACKAGE/com.byd.automap.meter.MeterActivity"

    /** The action that resolves to the meter activity while the package is enabled. */
    const val METER_ACTION = "$MAPS_PACKAGE.START_MAP_VIEW"

    /** The instrument map type: normal, and the one that puts the head unit's picture on the cluster. */
    const val TYPE_NORMAL = 1
    const val TYPE_ON_CLUSTER = 4

    private const val PROJECTION_PREFIX = "fission_bg_"

    /** The display the cluster's picture is made from, or null on a car that has none. */
    fun clusterDisplay(displays: List<NamedDisplay>): NamedDisplay? =
        displays.firstOrNull { it.name.startsWith(PROJECTION_PREFIX) }

    fun isSafePackage(name: String): Boolean = PassengerScreenParsing.isSafePackage(name)

    /** Apps that are never stopped when the cluster is given back: the system's own and the navigation app. */
    fun mayStop(packageName: String): Boolean = packageName != MAPS_PACKAGE && PassengerScreenParsing.mayClose(packageName)

    /** Starts [component] on [display] as the main user, as a full-screen task of its own. */
    fun startArguments(display: Int, component: String, flags: String = "0x10000000"): List<String> = listOf(
        "/system/bin/am", "start-activity", "--user", "0", "--display", display.toString(),
        "--windowingMode", "1", "--activity-multiple-task", "-f", flags, "-n", component,
    )

    /**
     * Brings the app's own task on [display] back in front, without starting a second copy of it: a start without
     * `--activity-multiple-task` finds the task that is already there ("its current task has been brought to the
     * front").
     */
    fun raiseArguments(display: Int, component: String): List<String> = listOf(
        "/system/bin/am", "start-activity", "--user", "0", "--display", display.toString(),
        "-f", "0x10020000", "-n", component,
    )

    private val DISPLAY_HEADER = Regex("""^Display #(\d+) """)

    /**
     * The id of the top-level task the main user's app has on a display other than [excludedDisplay], from
     * `dumpsys activity activities`; null when it has none there. Only the display sections' own task lines count
     * (two spaces of indent), not the flat list that follows them, and only user 0: the passenger screen's copy of
     * an app is another user's and is left where it is. A task record with no activity left (`sz=0`, the app has
     * died or finished) is not one that can be moved.
     */
    fun taskOutside(activityDump: String, packageName: String, excludedDisplay: Int): Int? {
        val task = Regex("""^ {2}\* Task\{[0-9a-f]+ #(\d+) [^}]*A=\d+:${Regex.escape(packageName)} U=0 [^}]*sz=[1-9]""")
        var display = -1
        for (line in activityDump.lineSequence()) {
            val header = DISPLAY_HEADER.find(line)
            if (header != null) {
                display = header.groupValues[1].toInt()
            } else if (display >= 0 && display != excludedDisplay) {
                task.find(line)?.let { return it.groupValues[1].toInt() }
            }
        }
        return null
    }

    private val TOP_RESUMED = Regex("""topResumedActivity=ActivityRecord\{([0-9a-f]+) """)
    private val VIEW = Regex("""\{[0-9a-f]+ (\S+) \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+).*?app:id/(\S+)\}""")
    private val PLAYBACK = Regex("""state=PlaybackState \{state=(\d+)""")

    /** Android's `PlaybackState.STATE_PLAYING`. */
    const val PLAYING = 3

    /** The record hash of the activity that is top-resumed on [display], to ask `dumpsys activity <hash>` for it. */
    fun topActivityHash(activityDump: String, display: Int): String? {
        val header = "Display #$display "
        val start = activityDump.indexOf(header).takeIf { it >= 0 } ?: return null
        val next = Regex("""Display #\d+ """).find(activityDump, start + header.length)?.range?.first ?: activityDump.length
        return TOP_RESUMED.find(activityDump.substring(start, next))?.groupValues?.get(1)
    }

    /**
     * Where to press to bring YouTube's player out of its miniplayer corner: the middle of the player when it is
     * visible and takes up less than half the window's width, read from the activity's view hierarchy
     * (`dumpsys activity <hash>`). Null when the player is large, or not there.
     */
    fun miniPlayerCenter(viewDump: String): Pair<Int, Int>? {
        var windowWidth = 0
        var player: List<Int>? = null
        for (line in viewDump.lineSequence()) {
            val m = VIEW.find(line) ?: continue
            if (!m.groupValues[1].startsWith("V")) continue
            val bounds = m.groupValues.subList(2, 6).map { it.toInt() }
            when (m.groupValues[6]) {
                "watch_while_layout_coordinator_layout" -> if (windowWidth == 0) windowWidth = bounds[2] - bounds[0]
                "watch_player" -> if (player == null) player = bounds
            }
        }
        val box = player ?: return null
        val width = box[2] - box[0]
        if (windowWidth == 0 || width <= 0 || box[3] - box[1] <= 0 || width * 2 >= windowWidth) return null
        return Pair((box[0] + box[2]) / 2, (box[1] + box[3]) / 2)
    }

    /** The playback state (`PlaybackState.STATE_*`) of the app's media session, from `dumpsys media_session`; null if none. */
    fun playbackStateOf(mediaSessionDump: String, packageName: String): Int? {
        val at = Regex("""package=${Regex.escape(packageName)}\b""").find(mediaSessionDump) ?: return null
        val tail = mediaSessionDump.substring(at.range.last, minOf(mediaSessionDump.length, at.range.last + 4000))
        return PLAYBACK.find(tail)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * The display (other than [excludedDisplay]) that holds a live task of the main user's [packageName], in the
     * order the dump lists them; null when it has none. Used to find where an app went when it left the cluster.
     */
    fun displayOf(activityDump: String, packageName: String, excludedDisplay: Int): Int? {
        val task = Regex("""^ {2}\* Task\{[0-9a-f]+ #\d+ [^}]*A=\d+:${Regex.escape(packageName)} U=0 [^}]*sz=[1-9]""")
        var display = -1
        for (line in activityDump.lineSequence()) {
            val header = DISPLAY_HEADER.find(line)
            if (header != null) {
                display = header.groupValues[1].toInt()
            } else if (display >= 0 && display != excludedDisplay && task.containsMatchIn(line)) {
                return display
            }
        }
        return null
    }

    /** What an app's media session says it is doing: its state, the title and where in it. */
    data class MediaNow(val state: Int, val title: String?, val positionMs: Long)

    private val POSITION = Regex("""state=PlaybackState \{state=(\d+), position=(-?\d+), buffered position=-?\d+, speed=(-?[0-9.]+), updated=(\d+)""")
    private val DESCRIPTION = Regex("""metadata: size=\d+, description=(.*)""")

    /**
     * The app's session from `dumpsys media_session`: [MediaNow.positionMs] is advanced from the time the state was
     * written ([elapsedNowMs] is the clock `updated=` uses) when it is playing. Null when the app has no session.
     */
    fun mediaNowOf(mediaSessionDump: String, packageName: String, elapsedNowMs: Long): MediaNow? {
        val at = Regex("""package=${Regex.escape(packageName)}\b""").find(mediaSessionDump) ?: return null
        val tail = mediaSessionDump.substring(at.range.last, minOf(mediaSessionDump.length, at.range.last + 4000))
        val m = POSITION.find(tail) ?: return null
        val state = m.groupValues[1].toInt()
        val speed = m.groupValues[3].toDoubleOrNull() ?: 1.0
        val updated = m.groupValues[4].toLong()
        var position = m.groupValues[2].toLong()
        if (state == PLAYING && updated > 0 && elapsedNowMs >= updated) position += ((elapsedNowMs - updated) * speed).toLong()
        val title = DESCRIPTION.find(tail)?.groupValues?.get(1)?.trim()?.let { titleOf(it) }
        return MediaNow(state, title, position.coerceAtLeast(0))
    }

    /** The title out of a media description "Title, Subtitle, Description" (the last two are often the same). */
    internal fun titleOf(description: String): String? {
        if (description.isBlank() || description == "null") return null
        val parts = description.split(", ")
        val title = if (parts.size >= 3 && parts[parts.size - 1] == parts[parts.size - 2]) {
            parts.dropLast(2).joinToString(", ")
        } else {
            parts.first()
        }
        return title.trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    /** Starts [packageName] on the link [url] as a task of its own on [display]. */
    fun viewArguments(display: Int, packageName: String, url: String): List<String> = listOf(
        "/system/bin/am", "start-activity", "--user", "0", "--display", display.toString(), "--windowingMode", "1",
        "--activity-multiple-task", "-f", "0x10000000", "-a", "android.intent.action.VIEW", "-d", url, "-p", packageName,
    )

    /** `key=value` words of a helper answer. */
    fun words(note: String?): Map<String, String> =
        note.orEmpty().split(' ').mapNotNull { word ->
            word.substringBefore('=', "").takeIf { it.isNotEmpty() && '=' in word }?.let { it to word.substringAfter('=') }
        }.toMap()
}

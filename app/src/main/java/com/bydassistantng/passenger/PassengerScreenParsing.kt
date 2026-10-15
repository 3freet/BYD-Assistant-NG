package com.bydassistantng.passenger

/** The window focus of one display: which Android user's app, and which of its activities. */
data class DisplayFocus(val user: Int, val packageName: String, val activity: String)

/** An app that has an activity on a display: [user] 0 is the main user, 999 the passenger screen's. */
data class OpenApp(val user: Int, val packageName: String)

/**
 * Reading `dumpsys window displays` and `dumpsys activity activities` for what is on one display, and the
 * checks on what may be sent to the helper. Pure text handling, so it runs in plain unit tests.
 */
object PassengerScreenParsing {
    private val FOCUS = Regex("""mCurrentFocus=Window\{[0-9a-f]+ u(\d+) ([\w.]+)/([\w.$]+)\}""")
    private val HISTORY = Regex("""Hist\s+#\d+: ActivityRecord\{[0-9a-f]+ u(\d+) ([\w.]+)/""")
    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    private val URL_CHARS = Regex("""https://[A-Za-z0-9._~:/?#@&=+%,;-]+""")

    /** The focused window of [displayId], or null when the display has none (or is not in the dump). */
    fun focusOf(windowDump: String, displayId: Int): DisplayFocus? {
        val start = windowDump.indexOf("Display: mDisplayId=$displayId ").takeIf { it >= 0 }
            ?: windowDump.indexOf("Display: mDisplayId=$displayId\n").takeIf { it >= 0 }
            ?: return null
        val next = windowDump.indexOf("Display: mDisplayId=", start + 1).takeIf { it >= 0 } ?: windowDump.length
        val match = FOCUS.find(windowDump.substring(start, next)) ?: return null
        val (user, pkg, activity) = match.destructured
        return DisplayFocus(user.toInt(), pkg, activity)
    }

    /** The apps with an activity on [displayId], top of the stack first, each once. */
    fun appsOn(activityDump: String, displayId: Int): List<OpenApp> {
        val header = "Display #$displayId "
        val start = activityDump.indexOf(header).takeIf { it >= 0 } ?: return emptyList()
        val next = Regex("""Display #\d+ """).find(activityDump, start + header.length)?.range?.first ?: activityDump.length
        return HISTORY.findAll(activityDump.substring(start, next))
            .map { OpenApp(it.groupValues[1].toInt(), it.groupValues[2]) }
            .distinct()
            .toList()
    }

    /** The id of the top-level task the app has (on any display), for `am display move-stack`; null when it has none. */
    fun rootTaskIdOf(activityDump: String, packageName: String): Int? {
        val pkg = Regex.escape(packageName)
        return Regex("""\* Task\{[0-9a-f]+ #(\d+) [^}]*A=\d+:$pkg[ }]""").find(activityDump)?.groupValues?.get(1)?.toIntOrNull()
    }

    // The passenger screen's brightness is the `screen_brightness` system setting of user 999 (0..255). Its own
    // slider runs from 10 to 255; below 10 the screen is nearly black.
    const val BRIGHTNESS_MIN = 10
    const val BRIGHTNESS_MAX = 255
    private const val BRIGHTNESS_STEP_PERCENT = 20

    /** The setting value for [percent] of the slider (clamped to 0..100). */
    fun brightnessValue(percent: Int): Int =
        BRIGHTNESS_MIN + (percent.coerceIn(0, 100) * (BRIGHTNESS_MAX - BRIGHTNESS_MIN) + 50) / 100

    /** How far up the slider the setting [value] is, in percent. */
    fun brightnessPercent(value: Int): Int =
        (((value.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX) - BRIGHTNESS_MIN) * 100 + (BRIGHTNESS_MAX - BRIGHTNESS_MIN) / 2) /
            (BRIGHTNESS_MAX - BRIGHTNESS_MIN))

    /**
     * What a request ("up", "down", "min", "max" or a percentage) makes of the brightness, as a setting value;
     * null when it is none of those. "up" and "down" move by a fifth of the slider from [current].
     */
    fun brightnessTarget(request: String?, current: Int?): Int? {
        val word = request?.trim()?.lowercase()?.removeSuffix("%")?.trim() ?: return null
        return when (word) {
            "max", "maximum", "full" -> BRIGHTNESS_MAX
            "min", "minimum" -> BRIGHTNESS_MIN
            "up", "brighter" -> brightnessValue(brightnessPercent(current ?: return null) + BRIGHTNESS_STEP_PERCENT)
            "down", "dimmer" -> brightnessValue(brightnessPercent(current ?: return null) - BRIGHTNESS_STEP_PERCENT)
            else -> word.toIntOrNull()?.takeIf { it in 0..100 }?.let { brightnessValue(it) }
        }
    }

    fun isSafePackage(name: String): Boolean = PACKAGE.matches(name) && name.length <= 120

    /** Only https links with plain URL characters: no spaces, quotes or anything a shell would read. */
    fun isSafeUrl(url: String): Boolean = url.length <= 600 && URL_CHARS.matches(url)

    /** Apps that are never closed from the passenger screen: the system's own windows and this app's host. */
    private val NEVER_CLOSE_PREFIXES = listOf(
        "android", "com.android.systemui", "com.android.launcher3", "com.android.settings", "com.byd.dicore",
        "com.byd.car.server", "com.xdja", "com.mediatek", "com.google.android.gms",
    )

    fun mayClose(packageName: String): Boolean = NEVER_CLOSE_PREFIXES.none { packageName == it || packageName.startsWith("$it.") }
}

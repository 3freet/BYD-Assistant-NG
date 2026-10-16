package com.bydassistantng.cluster

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import com.bydassistantng.passenger.PassengerScreenParsing
import com.bydassistantng.vehicle.CarFeatureIds
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import java.lang.reflect.Method

/**
 * `cluster <action> ...`: puts one app on the instrument cluster and takes it off again, from the vehicle helper
 * (ADB shell identity). Like the passenger screen it is a command of its own, not an entry of the vehicle command
 * registry, and it exists only in a debug (development) build.
 *
 *     cluster open <package> disable|keep [<link>]
 *                                     show the app on the cluster (the gauges are replaced by it), on a link; the
 *                                     second word is what is done with BYD Maps once the app has left the cluster
 *     cluster close [keep|disable]    give the cluster back; BYD Maps is disabled again unless "keep"
 *     cluster leaving <package>       the app is about to be taken off the cluster by something else: the gauges are
 *                                     put back first, so that the map does not show for a moment in between
 *     cluster status
 *
 * The sequence is the one the car's own map app uses, found on a Leopard 5 (docs/byd-internals/cluster-screen.md):
 * BYD's navigation app must be enabled and must have started its meter activity on the cluster's display; the
 * instrument map type must be 4; the app is then put on that display on top of the meter. An app that is already
 * running on another screen is moved there with its task (a second copy would stop what the first was playing);
 * otherwise it is started. The answer is given as soon as the app is in front, and a keeper thread brings it back
 * to the front (the same task, no second copy) should the navigation app's meter come in front of it while it
 * settles. The tool always assumes BYD Maps is disabled, which is why the first step enables it.
 * Giving the cluster back runs the other way: the car is told first, the app leaves, the navigation app goes, and
 * BYD Maps is disabled only once nothing is left on the cluster, so that no app is left stuck there. It is also
 * done on its own, whenever the app has left the cluster (moved to another screen, closed, crashed): a watcher
 * thread notices, gives the gauges back and, for a player that was playing, starts it again where it landed.
 *
 * There is no park check and no watchdog: this is for development builds on a car that stands still.
 */
internal object ClusterScreenCommand {
    const val NAME = "cluster"

    // Verified on the car this was found on; another firmware may number the same names differently.
    private const val INSTRUMENT_DEVICE = 1007
    private const val MAP_TYPE_STATE_ID = 1086337074
    private const val MAP_TYPE_CONTROL_ID = 1276157976

    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    private const val COMMAND_TIMEOUT_MS = 5_000L
    private const val ENABLE_WAIT_MS = 8_000L
    private const val TYPE_WAIT_MS = 3_000L
    private const val METER_WAIT_MS = 6_000L
    private const val POLL_MS = 250L

    // The navigation app, started cold, may bring its own meter to the front again a few seconds after it came up
    // and cover the app: the keeper holds the app in front until that time has passed.
    private const val SETTLE_MS = 7_000L
    private const val OPEN_CAP_MS = 12_000L
    private const val MOVE_WAIT_MS = 4_000L
    private const val LINK_WAIT_MS = 4_000L
    private const val RESTART_AFTER_MS = 2_000L

    // The navigation app answers the meter start by bringing its own meter to the front once more, within a second
    // or two. A video that is covered at that moment goes into a picture-in-picture corner and stops, so a player
    // is put on the cluster only after that.
    private const val REFRONT_MS = 2_500L
    private const val SETTLE_POLL_MS = 500L
    private const val KEEPER_JOIN_MS = 3_000L
    private const val WATCHER_JOIN_MS = 5_000L
    private const val WATCH_POLL_MS = 400L
    private const val LEFT_POLLS = 2
    private const val PLAYING_EVERY_POLLS = 4
    private const val RESTORE_TRIES = 8
    private const val RESTORE_POLL_MS = 500L

    // Giving the cluster back: how long to wait for the apps and then the meter to be gone, and for the cluster to
    // switch to its gauges before the navigation app is stopped.
    private const val CLEAR_WAIT_MS = 3_000L
    private const val GAUGES_SETTLE_MS = 1_200L

    // Keeps the started app in front while the navigation app settles, after the answer has been given.
    private var keeper: Thread? = null

    // Watches the app that was put on the cluster, and gives the gauges back when it has left.
    private var watcher: Thread? = null

    @Volatile private var watcherStop = false

    // The app the watcher is looking after, while it does.
    @Volatile private var watchedPackage: String? = null

    @Volatile private var keeperStop = false

    fun run(args: List<String>): VehicleDispatchResult = try {
        execute(args)
    } catch (t: Throwable) {
        VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, t.toString())
    }

    private fun execute(args: List<String>): VehicleDispatchResult = when (args.firstOrNull()) {
        "open" -> {
            val policy = args.getOrNull(2)
            if (policy != "disable" && policy != "keep") {
                invalid("expected: $NAME open <package> disable|keep [<link>]")
            } else {
                open(args.getOrNull(1), keepMaps = policy == "keep", url = args.getOrNull(3))
            }
        }
        "close" -> close(keepMaps = args.getOrNull(1) == "keep")
        "leaving" -> leaving(args.getOrNull(1))
        "status" -> status()
        else -> invalid("expected: $NAME open <package> | close [keep|disable] | status")
    }

    // ── actions ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun open(pkg: String?, keepMaps: Boolean, url: String?): VehicleDispatchResult {
        if (pkg == null || !ClusterScreenParsing.isSafePackage(pkg)) return invalid("not a package name")
        if (url != null && !PassengerScreenParsing.isSafeUrl(url)) return invalid("not a plain https link")
        if (pkg == ClusterScreenParsing.MAPS_PACKAGE) return invalid("BYD Maps is what carries the cluster; ask for another app")
        stopKeeper()
        stopWatcher()
        val context = systemContext()
        val display = clusterDisplay(context)
            ?: return VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, "this car has no cluster display")
        // A plain open needs the app's launcher entry; a link names its own screen, and the entry (when there is
        // one) is only what the app is brought back to the front with.
        val component = launcherActivity(pkg)
        if (url == null && component == null) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, "$pkg has no screen to open")
        }
        val map = MapType.of(context)
            ?: return VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, "this car does not number the map type as expected")
        val raise = component?.let { ClusterScreenParsing.raiseArguments(display, it) }
        // A copy of the app that is running on another screen is moved, not started again (a link is its own start).
        val existing = if (url == null) ClusterScreenParsing.taskOutside(activityDump(), pkg, display) else null
        val wasPlaying = playbackState(pkg) == ClusterScreenParsing.PLAYING

        // 1. BYD Maps on (the tool assumes it is off). A copy that is still running is left alone: stopping it makes
        //    the car put the map type back to 1 a moment later, over what is written next.
        exec(listOf("/system/bin/pm", "enable", "--user", "0", ClusterScreenParsing.MAPS_PACKAGE))
        if (!waitUntil(ENABLE_WAIT_MS) { mapsEnabled() }) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "BYD Maps did not become available")
        }

        // 2. The instrument map type: the cluster is to take the head unit's picture.
        if (!map.write(ClusterScreenParsing.TYPE_ON_CLUSTER)) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "the car did not take the map type")
        }

        // 3. BYD's meter activity on the cluster display: this is what makes the car use that display.
        exec(ClusterScreenParsing.startArguments(display, ClusterScreenParsing.METER_COMPONENT, "0x18000000"))
        val meterAt = SystemClock.uptimeMillis()
        if (!waitUntil(METER_WAIT_MS) { appsOnDisplay(display).contains(ClusterScreenParsing.MAPS_PACKAGE) }) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "BYD Maps did not put its screen on the cluster")
        }

        // 4a. A link (a video): a task of its own on the link, once the meter has had its say.
        if (url != null) {
            waitForRefront(meterAt, map)
            return openLink(display, pkg, url, meterAt, map, raise, keepMaps)
        }

        // 4b. Moved: the task the app already has, with its process and its state. A player that was playing is
        //     looked after by the keeper: some players stop and shrink to a corner when they change display.
        if (existing != null) {
            if (wasPlaying) waitForRefront(meterAt, map)
            exec(listOf("/system/bin/am", "display", "move-stack", existing.toString(), display.toString()))
            if (waitUntil(MOVE_WAIT_MS) { topPackage(display) == pkg }) {
                startKeeper(display, pkg, raise, meterAt, map, wasPlaying)
                startWatcher(display, pkg, keepMaps)
                return VehicleDispatchResult.Success("moved=$pkg display=$display type=${map.read()}")
            }
            // Not taken (a task record without its app, say): start it instead.
        }

        // 4c. Started, on top of the meter. It is answered as soon as the app is in front; a keeper then holds it
        //     there while the navigation app settles.
        val start = ClusterScreenParsing.startArguments(display, component!!)
        exec(start)
        var startedAt = SystemClock.uptimeMillis()
        val cap = startedAt + OPEN_CAP_MS
        while (true) {
            Thread.sleep(POLL_MS)
            // The car or the navigation app may put the map type back while it starts up: keep it where it is wanted.
            if (map.read() != ClusterScreenParsing.TYPE_ON_CLUSTER) map.write(ClusterScreenParsing.TYPE_ON_CLUSTER)
            val top = topPackage(display)
            if (top == pkg) {
                startKeeper(display, pkg, raise ?: start, meterAt, map)
                startWatcher(display, pkg, keepMaps)
                return VehicleDispatchResult.Success("opened=$pkg display=$display type=${map.read()}")
            }
            val now = SystemClock.uptimeMillis()
            if (now - startedAt >= RESTART_AFTER_MS) {
                exec(start)
                startedAt = now
            }
            if (now >= cap) {
                return VehicleDispatchResult.Failure(
                    VehicleDispatchError.INVOCATION_FAILED,
                    "$pkg did not come up on the cluster" + (top?.let { " (it shows $it)" } ?: ""),
                )
            }
        }
    }

    /** Waits until [REFRONT_MS] have passed since the meter was started, keeping the map type where it is wanted. */
    private fun waitForRefront(meterAt: Long, map: MapType) {
        while (SystemClock.uptimeMillis() - meterAt < REFRONT_MS) {
            Thread.sleep(POLL_MS)
            if (map.read() != ClusterScreenParsing.TYPE_ON_CLUSTER) map.write(ClusterScreenParsing.TYPE_ON_CLUSTER)
        }
    }

    /**
     * The app on a link, as a task of its own. Some apps (YouTube) start their screen from the link on the main
     * display whatever was asked; that task is then moved over.
     */
    private fun openLink(
        display: Int,
        pkg: String,
        url: String,
        meterAt: Long,
        map: MapType,
        raise: List<String>?,
        keepMaps: Boolean,
    ): VehicleDispatchResult {
        exec(ClusterScreenParsing.viewArguments(display, pkg, url))
        var how = "shown"
        var shown = waitUntil(LINK_WAIT_MS) { topPackage(display) == pkg }
        if (!shown) {
            val task = ClusterScreenParsing.taskOutside(activityDump(), pkg, display)
            if (task != null) {
                exec(listOf("/system/bin/am", "display", "move-stack", task.toString(), display.toString()))
                shown = waitUntil(MOVE_WAIT_MS) { topPackage(display) == pkg }
                how = "moved"
            }
        }
        if (!shown) {
            return VehicleDispatchResult.Failure(
                VehicleDispatchError.INVOCATION_FAILED,
                "$pkg did not come up on the cluster" + (topPackage(display)?.let { " (it shows $it)" } ?: ""),
            )
        }
        startKeeper(display, pkg, raise, meterAt, map)
        startWatcher(display, pkg, keepMaps)
        return VehicleDispatchResult.Success("opened=$pkg display=$display link=$how type=${map.read()}")
    }

    /**
     * Gives the cluster back, in this order so that no app is left stuck on it: the car is told to show its gauges
     * while the navigation app is still there to hand the picture back, the apps leave, the cluster gets a moment,
     * the navigation app goes, and only when nothing is left on the cluster is BYD Maps disabled.
     */
    private fun close(keepMaps: Boolean): VehicleDispatchResult {
        stopKeeper()
        stopWatcher()
        return giveBack(keepMaps)
    }

    private fun giveBack(keepMaps: Boolean): VehicleDispatchResult {
        // The keeper puts the map type back to "on the cluster" while it runs: it must be gone first, or it can
        // undo the gauges a moment after they were given back and leave the map on the cluster.
        stopKeeper()
        val context = systemContext()
        val display = clusterDisplay(context)
        val map = MapType.of(context)
        val stopped = if (display != null) appsOnDisplay(display).filter { ClusterScreenParsing.mayStop(it) } else emptyList()

        var typeBack = map?.write(ClusterScreenParsing.TYPE_NORMAL) ?: false
        for (pkg in stopped) exec(listOf("/system/bin/am", "force-stop", pkg))
        if (display != null && stopped.isNotEmpty()) {
            waitUntil(CLEAR_WAIT_MS) { appsOnDisplay(display).none { it != ClusterScreenParsing.MAPS_PACKAGE } }
            Thread.sleep(GAUGES_SETTLE_MS)
        }
        exec(listOf("/system/bin/am", "force-stop", ClusterScreenParsing.MAPS_PACKAGE))
        val clear = display == null || waitUntil(CLEAR_WAIT_MS) { appsOnDisplay(display).isEmpty() }
        // The car puts the type back by itself when the navigation app stops; make sure of it.
        if (map != null && map.read() != ClusterScreenParsing.TYPE_NORMAL) typeBack = map.write(ClusterScreenParsing.TYPE_NORMAL)

        if (!clear) {
            // Never disable BYD Maps under an app that is still on the cluster: it would be stuck there.
            return VehicleDispatchResult.Failure(
                VehicleDispatchError.INVOCATION_FAILED,
                "something is still on the cluster, so BYD Maps was left enabled",
            )
        }
        if (!keepMaps) exec(listOf("/system/bin/pm", "disable-user", "--user", "0", ClusterScreenParsing.MAPS_PACKAGE))
        return VehicleDispatchResult.Success(
            "closed=${stopped.joinToString(",")} type=${if (typeBack) "restored" else "unchecked"} maps=${if (keepMaps) "enabled" else "disabled"}",
        )
    }

    // ── the watcher ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The rule that the cluster goes back to its gauges whenever the app has left it, by whatever way: moved to the
     * main or the passenger screen, closed, or ended. The app is looked for on the cluster display every
     * [WATCH_POLL_MS]; once it is no longer there (sure at once if it went to another screen, twice in a row if it is
     * nowhere, to ride out a restart) the gauges are given back as [close] does it, and a player that was playing is
     * started again where it landed. An app that crashes is noticed from the crash log at once: Android keeps its
     * window for a couple of seconds after the crash, and the map would show through all that time.
     */
    private fun startWatcher(display: Int, pkg: String, keepMaps: Boolean) {
        watcherStop = false
        watchedPackage = pkg
        watcher = Thread {
            runCatching {
                var absent = 0
                var polls = 0
                var wasPlaying = false
                val crashesBefore = lastCrashOf(pkg)
                while (!watcherStop) {
                    Thread.sleep(WATCH_POLL_MS)
                    if (watcherStop) break
                    if (lastCrashOf(pkg) != crashesBefore) {
                        stopKeeper()
                        watcher = null
                        watchedPackage = null
                        giveBack(keepMaps)
                        break
                    }
                    val dump = activityDump()
                    if (PassengerScreenParsing.appsOn(dump, display).any { it.packageName == pkg }) {
                        absent = 0
                        if (polls++ % PLAYING_EVERY_POLLS == 0 && playbackState(pkg) == ClusterScreenParsing.PLAYING) wasPlaying = true
                        continue
                    }
                    val landed = ClusterScreenParsing.displayOf(dump, pkg, display)
                    if (landed == null && ++absent < LEFT_POLLS) continue
                    if (landed != null && wasPlaying) {
                        Thread { runCatching { restorePlayback(pkg, landed) } }.apply { isDaemon = true }.start()
                    }
                    watcher = null
                    watchedPackage = null
                    giveBack(keepMaps)
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopWatcher() {
        watcherStop = true
        watcher?.let { if (it !== Thread.currentThread()) runCatching { it.join(WATCHER_JOIN_MS) } }
        watcher = null
        watchedPackage = null
    }

    /**
     * A player that changed screen stops, and YouTube comes back as its miniplayer: the miniplayer is pressed on the
     * screen the app landed on, and the player started again.
     */
    private fun restorePlayback(pkg: String, display: Int) {
        var expanded = false
        var resumed = false
        repeat(RESTORE_TRIES) {
            Thread.sleep(RESTORE_POLL_MS)
            if (!expanded && pkg == YOUTUBE_PACKAGE) {
                val hash = ClusterScreenParsing.topActivityHash(activityDump(), display)
                val center = hash?.let { ClusterScreenParsing.miniPlayerCenter(exec(listOf("/system/bin/dumpsys", "activity", it))) }
                if (center != null) {
                    exec(listOf("/system/bin/input", "-d", display.toString(), "tap", center.first.toString(), center.second.toString()))
                    expanded = true
                }
            }
            if (!resumed && playbackState(pkg).let { it != null && it != ClusterScreenParsing.PLAYING }) {
                exec(listOf("/system/bin/cmd", "media_session", "dispatch", "play"))
                resumed = true
            }
        }
    }

    // ── the keeper ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Holds [pkg] in front, and the map type where it is wanted, until the navigation app has settled. [raise] is
     * what brings the app back to the front when something came in front of it; null when there is no such command.
     */
    private fun startKeeper(
        display: Int,
        pkg: String,
        raise: List<String>?,
        meterAt: Long,
        map: MapType,
        wasPlaying: Boolean = false,
    ) {
        keeperStop = false
        keeper = Thread {
            runCatching {
                var expanded = false
                var resumed = false
                while (!keeperStop && SystemClock.uptimeMillis() - meterAt < SETTLE_MS) {
                    Thread.sleep(SETTLE_POLL_MS)
                    if (keeperStop) break
                    if (map.read() != ClusterScreenParsing.TYPE_ON_CLUSTER) map.write(ClusterScreenParsing.TYPE_ON_CLUSTER)
                    val dump = activityDump()
                    val top = PassengerScreenParsing.appsOn(dump, display).firstOrNull()?.packageName
                    if (top != pkg) {
                        // Covered, and still there: bring it back. Gone (moved on): leave it be.
                        val stillThere = PassengerScreenParsing.appsOn(dump, display).any { it.packageName == pkg }
                        if (!keeperStop && raise != null && stillThere) exec(raise)
                        continue
                    }
                    if (!wasPlaying) continue
                    // The player that was playing: out of its miniplayer corner, and playing again if it stopped.
                    if (!expanded && pkg == YOUTUBE_PACKAGE) {
                        val hash = ClusterScreenParsing.topActivityHash(dump, display)
                        val center = hash?.let { ClusterScreenParsing.miniPlayerCenter(exec(listOf("/system/bin/dumpsys", "activity", it))) }
                        if (center != null && !keeperStop) {
                            exec(listOf("/system/bin/input", "-d", display.toString(), "tap", center.first.toString(), center.second.toString()))
                            expanded = true
                        }
                    }
                    if (!resumed && !keeperStop && playbackState(pkg).let { it != null && it != ClusterScreenParsing.PLAYING }) {
                        exec(listOf("/system/bin/cmd", "media_session", "dispatch", "play"))
                        resumed = true
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The time of the app's last crash in the system's crash log (`Process: <package>, PID: …`), or null if there is
     * none in it. A change of it between two looks means the app crashed in between.
     */
    private fun lastCrashOf(pkg: String): String? {
        val log = exec(listOf("/system/bin/logcat", "-b", "crash", "-d", "-v", "time", "-t", "80"))
        return Regex("""(?m)^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+) E/AndroidRuntime\(\s*\d+\): Process: ${Regex.escape(pkg)},""")
            .findAll(log).lastOrNull()?.groupValues?.get(1)
    }

    private fun playbackState(pkg: String): Int? =
        ClusterScreenParsing.playbackStateOf(exec(listOf("/system/bin/dumpsys", "media_session")), pkg)

    /** Ends the keeper and waits for it, so that nothing it does can land after the command that follows. */
    private fun stopKeeper() {
        keeperStop = true
        keeper?.let { runCatching { it.join(KEEPER_JOIN_MS) } }
        keeper = null
    }

    /**
     * The app is about to leave the cluster (the main screen's own "open" is going to relocate it): the car is told
     * to show its gauges now. Without it the navigation app's map shows between the app going and the watcher
     * noticing. The watcher stays, and finishes the job once the app has gone.
     */
    private fun leaving(pkg: String?): VehicleDispatchResult {
        if (pkg == null || !ClusterScreenParsing.isSafePackage(pkg)) return invalid("not a package name")
        if (watchedPackage != pkg) return VehicleDispatchResult.Success("leaving=ignored")
        stopKeeper()
        val restored = MapType.of(systemContext())?.write(ClusterScreenParsing.TYPE_NORMAL) ?: false
        return VehicleDispatchResult.Success("leaving=$pkg type=${if (restored) "restored" else "unchecked"}")
    }

    private fun status(): VehicleDispatchResult {
        val context = systemContext()
        val display = clusterDisplay(context)
        val type = MapType.of(context)?.read()
        val top = display?.let { topPackage(it) }
        val apps = display?.let { appsOnDisplay(it).filter { p -> p != ClusterScreenParsing.MAPS_PACKAGE } }.orEmpty()
        return VehicleDispatchResult.Success(
            "type=${type ?: "unknown"} top=${top ?: "none"} apps=${apps.joinToString(",")} " +
                "maps=${if (mapsEnabled()) "enabled" else "disabled"} display=${display ?: "none"}",
        )
    }

    // ── the instrument map type ──────────────────────────────────────────────────────────────────────────────

    /** The car's instrument map type: read through the state id, written through the control id, confirmed by reading. */
    private class MapType(private val device: Any, private val get: Method, private val set: Method) {
        fun read(): Int? = runCatching { get.invoke(device, INSTRUMENT_DEVICE, MAP_TYPE_STATE_ID) as? Int }.getOrNull()

        fun write(value: Int): Boolean {
            set.invoke(device, INSTRUMENT_DEVICE, intArrayOf(MAP_TYPE_CONTROL_ID), intArrayOf(value))
            return waitUntil(TYPE_WAIT_MS) { read() == value }
        }

        companion object {
            fun of(context: Context): MapType? = runCatching {
                // Only written when the car's framework gives the names the ids this was found with.
                if (CarFeatureIds.idOf("Instrument", "INSTRUMENT_NAVI_TYPE") != MAP_TYPE_STATE_ID) return null
                if (CarFeatureIds.idOf("Instrument", "INSTRUMENT_NAVI_TYPE_SET") != MAP_TYPE_CONTROL_ID) return null
                val deviceClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
                val device = deviceClass.getMethod("getInstance", Context::class.java).invoke(null, context) ?: return null
                val base = Class.forName("android.hardware.bydauto.AbsBYDAutoDevice")
                val get = base.getDeclaredMethod("get", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
                val set = base.getDeclaredMethod(
                    "set", Int::class.javaPrimitiveType, IntArray::class.java, IntArray::class.java,
                ).apply { isAccessible = true }
                MapType(device, get, set)
            }.getOrNull()
        }
    }

    // ── reading the car's windows and packages ───────────────────────────────────────────────────────────────

    private fun clusterDisplay(context: Context): Int? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return ClusterScreenParsing.clusterDisplay(manager.displays.map { NamedDisplay(it.displayId, it.name.orEmpty()) })?.id
    }

    private fun activityDump(): String = exec(listOf("/system/bin/dumpsys", "activity", "activities"))

    private fun appsOnDisplay(display: Int): List<String> =
        PassengerScreenParsing.appsOn(activityDump(), display).map { it.packageName }

    /**
     * The app in front on [display]: the first of its activity stack. The window focus is not used, because the
     * cluster's display is not one the window manager gives a focused window.
     */
    private fun topPackage(display: Int): String? = appsOnDisplay(display).firstOrNull()

    /** True once the package manager resolves the meter's action: the package is enabled and known. */
    private fun mapsEnabled(): Boolean =
        exec(listOf("/system/bin/cmd", "package", "query-activities", "-a", ClusterScreenParsing.METER_ACTION)).contains("1 activities found")

    /** `package/activity` of the app's launcher entry for the main user, from the package manager's own resolver. */
    private fun launcherActivity(pkg: String): String? {
        val answer = exec(
            listOf(
                "/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", "0",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg,
            ),
        )
        return answer.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("$pkg/") }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (condition()) return true
            if (SystemClock.uptimeMillis() >= deadline) return false
            Thread.sleep(POLL_MS)
        }
    }

    /** Runs a program without a shell and returns what it printed (stdout and stderr), or "" when it timed out. */
    private fun exec(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } } }
        reader.start()
        // Process.waitFor(timeout) needs API 26: wait on a thread instead.
        val waiter = Thread { runCatching { process.waitFor() } }
        waiter.start()
        waiter.join(COMMAND_TIMEOUT_MS)
        if (waiter.isAlive) {
            process.destroy()
            return ""
        }
        reader.join(1_000L)
        return out.toString()
    }

    private fun invalid(detail: String) = VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, detail)

    /** The helper this runs in has already started the system context: reuse it. */
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }
}

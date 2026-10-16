package com.bydassistantng.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterScreenParsingTest {
    private val displays = listOf(
        NamedDisplay(0, "ivi"),
        NamedDisplay(2, "fse"),
        NamedDisplay(4, "shared_fission_bg_XDJAScreenProjection_0"),
        NamedDisplay(3, "fission_bg_XDJAScreenProjection"),
        NamedDisplay(5, "shared_fission_bg_XDJAScreenProjection_1"),
    )

    @Test
    fun theClusterDisplayIsTheProjectionNotItsSharedCopies() {
        assertEquals(3, ClusterScreenParsing.clusterDisplay(displays)?.id)
    }

    @Test
    fun aCarWithoutTheProjectionDisplayHasNoCluster() {
        assertNull(ClusterScreenParsing.clusterDisplay(displays.filter { it.id < 3 }))
        assertNull(ClusterScreenParsing.clusterDisplay(displays.filter { it.name.startsWith("shared_") }))
    }

    @Test
    fun theMapTypesAreTheOnesFoundOnTheCar() {
        assertEquals(1, ClusterScreenParsing.TYPE_NORMAL)
        assertEquals(4, ClusterScreenParsing.TYPE_ON_CLUSTER)
    }

    @Test
    fun theNavigationAppAndTheSystemAreNeverStoppedAsTheClusterApp() {
        assertFalse(ClusterScreenParsing.mayStop(ClusterScreenParsing.MAPS_PACKAGE))
        assertFalse(ClusterScreenParsing.mayStop("com.xdja.containerservice"))
        assertFalse(ClusterScreenParsing.mayStop("android"))
        assertTrue(ClusterScreenParsing.mayStop("ru.yandex.yandexnavi"))
    }

    @Test
    fun anAppIsStartedAsTheMainUsersOwnFullScreenTaskOnTheDisplay() {
        val line = ClusterScreenParsing.startArguments(3, "ru.yandex.yandexnavi/.core.NavigatorActivity")
        assertEquals(
            listOf(
                "/system/bin/am", "start-activity", "--user", "0", "--display", "3", "--windowingMode", "1",
                "--activity-multiple-task", "-f", "0x10000000", "-n", "ru.yandex.yandexnavi/.core.NavigatorActivity",
            ),
            line,
        )
        // The meter activity is started the way the navigation app starts it itself: new task and multiple task.
        assertEquals("0x18000000", ClusterScreenParsing.startArguments(3, ClusterScreenParsing.METER_COMPONENT, "0x18000000")[10])
    }

    @Test
    fun anAppIsBroughtBackWithoutASecondCopy() {
        val line = ClusterScreenParsing.raiseArguments(3, "com.google.android.youtube/.app.honeycomb.Shell\$HomeActivity")
        // New task + reorder to front, and no "multiple task": the task that is there is the one brought forward.
        assertFalse("--activity-multiple-task" in line)
        assertEquals("0x10020000", line[line.indexOf("-f") + 1])
        assertEquals("3", line[line.indexOf("--display") + 1])
    }

    @Test
    fun theMeterIsBydNavigationsOwnClusterActivity() {
        assertEquals("com.byd.naviauto/com.byd.automap.meter.MeterActivity", ClusterScreenParsing.METER_COMPONENT)
        assertEquals("com.byd.naviauto.START_MAP_VIEW", ClusterScreenParsing.METER_ACTION)
    }

    @Test
    fun onlyPlainPackageNamesAreAccepted() {
        assertTrue(ClusterScreenParsing.isSafePackage("ru.yandex.yandexnavi"))
        assertFalse(ClusterScreenParsing.isSafePackage("x; reboot"))
        assertFalse(ClusterScreenParsing.isSafePackage("../etc"))
    }

    @Test
    fun anAnswerIsKeyValueWords() {
        val words = ClusterScreenParsing.words("type=4 top=ru.yandex.yandexnavi apps=ru.yandex.yandexnavi maps=enabled display=3")
        assertEquals("4", words["type"])
        assertEquals("ru.yandex.yandexnavi", words["top"])
        assertEquals("enabled", words["maps"])
        assertTrue(ClusterScreenParsing.words(null).isEmpty())
    }

    private val dump = """
        Display #0 (activities from top to bottom):
          * Task{122b9d6 #206 type=standard A=10187:com.bydassistantng.dev U=0 visible=true mode=fullscreen sz=1}
            topResumedActivity=ActivityRecord{4510b44 u0 com.bydassistantng.dev/com.bydassistantng.ui.MainActivity} t206}
          * Task{4a54425 #228 type=standard A=10186:com.google.android.youtube U=0 visible=false mode=fullscreen sz=0}
          * Task{641912a #224 type=standard A=10186:com.google.android.youtube U=0 visible=false mode=fullscreen sz=1}
            * Task{d1e6263 #173 type=home I=com.android.launcher3/.home.MainActivity U=0 rootTaskId=1 visible=false sz=1}
        Display #2 (activities from top to bottom):
          * Task{fd1387 #153 type=undefined ?? U=0 visible=true mode=fullscreen sz=1}
            * Task{6edfbd9 #99900006 type=standard A=99910065:com.byd.mediacenter U=999 rootTaskId=153 visible=true sz=1}
          * Task{aa11 #301 type=standard A=99910186:com.google.android.youtube U=999 visible=true sz=1}
        Display #3 (activities from top to bottom):
          * Task{ed6c28d #226 type=standard A=10065:com.byd.naviauto.meter U=0 visible=true mode=fullscreen sz=1}
          * Task{2de3ef7 #208 type=standard A=10159:ru.yandex.yandexnavi U=0 visible=false mode=fullscreen sz=1}
        Display #5 (activities from top to bottom):
          ResumedActivity: ActivityRecord{4510b44 u0 com.bydassistantng.dev/com.bydassistantng.ui.MainActivity} t206}
              * Task{9506faa #225 type=standard A=10186:com.google.android.youtube U=0 visible=true mode=fullscreen sz=1}
    """.trimIndent()

    @Test
    fun aRunningAppIsFoundOnAnotherScreenToBeMoved() {
        assertEquals(224, ClusterScreenParsing.taskOutside(dump, "com.google.android.youtube", excludedDisplay = 3))
    }

    @Test
    fun aTaskWithNoActivityLeftIsNotOneToMove() {
        // #228 is listed first, with no activity (the app died); the live one is #224.
        assertEquals(224, ClusterScreenParsing.taskOutside(dump, "com.google.android.youtube", excludedDisplay = 3))
        val onlyDead = "Display #0 (activities from top to bottom):\n" +
            "  * Task{4a54425 #228 type=standard A=10186:com.google.android.youtube U=0 visible=false sz=0 mBydNeedsZBoost=false}\n"
        assertNull(ClusterScreenParsing.taskOutside(onlyDead, "com.google.android.youtube", excludedDisplay = 3))
    }

    @Test
    fun anAppAlreadyOnTheClusterIsNotMovedFromItself() {
        assertNull(ClusterScreenParsing.taskOutside(dump, "ru.yandex.yandexnavi", excludedDisplay = 3))
        assertEquals(208, ClusterScreenParsing.taskOutside(dump, "ru.yandex.yandexnavi", excludedDisplay = 0))
    }

    @Test
    fun thePassengerScreensCopyAndTheFlatListAreNotTaken() {
        // User 999 holds a copy on display 2, and the flat list after the last display repeats every task.
        assertEquals(224, ClusterScreenParsing.taskOutside(dump, "com.google.android.youtube", excludedDisplay = 3))
        assertNull(ClusterScreenParsing.taskOutside(dump, "com.byd.mediacenter", excludedDisplay = 3))
        assertNull(ClusterScreenParsing.taskOutside(dump, "com.google.android.youtube", excludedDisplay = 0))
    }

    @Test
    fun theTopActivityOfTheClusterDisplayIsFoundByItsRecordHash() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{122b9d6 #206 type=standard A=10187:com.bydassistantng.dev U=0 visible=true sz=1}
                topResumedActivity=ActivityRecord{4510b44 u0 com.bydassistantng.dev/com.bydassistantng.ui.MainActivity} t206}
            Display #3 (activities from top to bottom):
              * Task{9506faa #225 type=standard A=10186:com.google.android.youtube U=0 visible=true sz=1}
                topResumedActivity=ActivityRecord{4bb81d8 u0 com.google.android.youtube/.app.honeycomb.Shell${'$'}HomeActivity} t225}
            Display #4 (activities from top to bottom):
        """.trimIndent()
        assertEquals("4bb81d8", ClusterScreenParsing.topActivityHash(dump, 3))
        assertEquals("4510b44", ClusterScreenParsing.topActivityHash(dump, 0))
        assertNull(ClusterScreenParsing.topActivityHash(dump, 4))
    }

    private fun view(id: String, flags: String, bounds: String) =
        "                          android.view.View{8fa3140 $flags ........ $bounds #7f0b1815 app:id/$id}"

    @Test
    fun aPlayerInItsCornerIsPressedAtItsMiddle() {
        val dump = listOf(
            view("watch_while_layout_coordinator_layout", "V.E......", "0,0-1920,720"),
            view("watch_player", "V.E......", "1512,384-1896,600"),
            view("modern_miniplayer_close", "V.ED.....", "288,0-384,96"),
        ).joinToString("\n")
        assertEquals(Pair(1704, 492), ClusterScreenParsing.miniPlayerCenter(dump))
    }

    @Test
    fun aPlayerThatFillsTheWindowOrIsNotThereIsLeftAlone() {
        val full = listOf(
            view("watch_while_layout_coordinator_layout", "V.E......", "0,0-1920,720"),
            view("watch_player", "V.E......", "0,0-1920,720"),
        ).joinToString("\n")
        assertNull(ClusterScreenParsing.miniPlayerCenter(full))
        val hidden = listOf(
            view("watch_while_layout_coordinator_layout", "V.E......", "0,0-1920,720"),
            view("watch_player", "G.E......", "1512,384-1896,600"),
        ).joinToString("\n")
        assertNull(ClusterScreenParsing.miniPlayerCenter(hidden))
        assertNull(ClusterScreenParsing.miniPlayerCenter(""))
    }

    @Test
    fun thePlaybackStateOfAnAppsSessionIsRead() {
        val dump = """
            Sessions Stack
              package=com.byd.mediacenter
                state=PlaybackState {state=2, position=0, buffered position=0}
              package=com.google.android.youtube
                launchIntent=PendingIntent{61baf88}
                active=true
                state=PlaybackState {state=1, position=4068, buffered position=0}
        """.trimIndent()
        assertEquals(1, ClusterScreenParsing.playbackStateOf(dump, "com.google.android.youtube"))
        assertEquals(2, ClusterScreenParsing.playbackStateOf(dump, "com.byd.mediacenter"))
        assertNull(ClusterScreenParsing.playbackStateOf(dump, "ru.yandex.yandexnavi"))
        assertEquals(3, ClusterScreenParsing.PLAYING)
    }

    @Test
    fun whereAnAppWentWhenItLeftTheCluster() {
        // The live YouTube task is on display 0; the passenger screen's copy (user 999) and dead records do not count.
        assertEquals(0, ClusterScreenParsing.displayOf(dump, "com.google.android.youtube", excludedDisplay = 3))
        assertNull(ClusterScreenParsing.displayOf(dump, "com.google.android.youtube", excludedDisplay = 0))
        // Still on the cluster only: nowhere else.
        assertNull(ClusterScreenParsing.displayOf(dump, "ru.yandex.yandexnavi", excludedDisplay = 3))
    }

    @Test
    fun aLinkIsOpenedAsATaskOfItsOwnOnTheDisplay() {
        val line = ClusterScreenParsing.viewArguments(3, "com.google.android.youtube", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s")
        assertEquals("3", line[line.indexOf("--display") + 1])
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s", line[line.indexOf("-d") + 1])
        assertEquals("com.google.android.youtube", line[line.indexOf("-p") + 1])
        assertTrue("--activity-multiple-task" in line)
    }

    @Test
    fun theClusterIsOffUntilSwitchedOn() {
        assertFalse(ClusterOptions.OFF.enabled)
        // Both switches are off until the user turns them on.
        assertFalse(ClusterOptions.OFF.keepMapsDisabled)
        assertFalse(ClusterOptions().keepMapsDisabled)
    }
}

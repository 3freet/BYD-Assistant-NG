package com.bydassistantng.passenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassengerScreenParsingTest {
    // The shape of the car's `dumpsys window displays`: one block per display, each with its own focus lines.
    private val windows = """
  Display: mDisplayId=0 rootTasks=10
    init=2560x1440 320dpi cur=2560x1440 app=2560x1320
  mCurrentFocus=Window{4189bc1 u0 com.bydassistantng.dev/com.bydassistantng.ui.MainActivity}
  mFocusedApp=ActivityRecord{6e5e489 u0 com.bydassistantng.dev/com.bydassistantng.ui.MainActivity} t82}
  Display: mDisplayId=2 rootTasks=2
    init=1920x720 240dpi cur=1920x720 app=1764x720
  mCurrentFocus=Window{523a4c5 u0 com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.InternalMainActivity}
  mFocusedApp=ActivityRecord{8db6f44 u0 com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.InternalMainActivity} t33}
  Display: mDisplayId=3 rootTasks=0
  mCurrentFocus=null
  mFocusedApp=null
"""

    private val activities = """
Display #2 (activities from top to bottom):
  * Task{fa30978 #2 type=undefined U=0 visible=true}
    * Task{c0ffee #33 type=standard A=10151:com.google.android.youtube U=0 visible=true}
      * Hist  #0: ActivityRecord{8db6f44 u0 com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.InternalMainActivity} t33}
        rootOfTask=true task=Task{c0ffee}
    * Task{635fecc #99900004 type=home I=com.android.launcher3.fse/com.android.launcher3.home.MainActivity U=999 visible=false}
      * Hist  #0: ActivityRecord{60502a u999 com.android.launcher3.fse/com.android.launcher3.home.MainActivity} t99900004}
Display #0 (activities from top to bottom):
  * Task{1 #80 type=standard A=10151:org.triprecorderng U=0}
    * Hist  #0: ActivityRecord{2348494 u0 org.triprecorderng/.MainActivity} t80}
Display #3 (activities from top to bottom):
"""

    @Test
    fun theFocusOfEachDisplayComesFromItsOwnBlock() {
        assertEquals(
            DisplayFocus(0, "com.google.android.youtube", "com.google.android.apps.youtube.app.watchwhile.InternalMainActivity"),
            PassengerScreenParsing.focusOf(windows, 2),
        )
        assertEquals("com.bydassistantng.dev", PassengerScreenParsing.focusOf(windows, 0)?.packageName)
    }

    @Test
    fun aDisplayWithNothingFocusedOrNotListedHasNoFocus() {
        assertNull(PassengerScreenParsing.focusOf(windows, 3))
        assertNull(PassengerScreenParsing.focusOf(windows, 7))
    }

    @Test
    fun theFocusKeepsTheUserOfAPassengerScreenApp() {
        val dump = "  Display: mDisplayId=2 rootTasks=1\n  mCurrentFocus=Window{2d30c42 u999 com.android.launcher3.fse/com.android.launcher3.home.MainActivity}\n"
        assertEquals(DisplayFocus(999, "com.android.launcher3.fse", "com.android.launcher3.home.MainActivity"), PassengerScreenParsing.focusOf(dump, 2))
    }

    @Test
    fun theOpenAppsOfADisplayAreListedOnceTopFirstAndOtherDisplaysAreIgnored() {
        assertEquals(
            listOf(OpenApp(0, "com.google.android.youtube"), OpenApp(999, "com.android.launcher3.fse")),
            PassengerScreenParsing.appsOn(activities, 2),
        )
        assertEquals(listOf(OpenApp(0, "org.triprecorderng")), PassengerScreenParsing.appsOn(activities, 0))
        assertEquals(emptyList<OpenApp>(), PassengerScreenParsing.appsOn(activities, 3))
        assertEquals(emptyList<OpenApp>(), PassengerScreenParsing.appsOn(activities, 9))
    }

    @Test
    fun displayTwoIsNotConfusedWithADisplayWhoseIdStartsTheSame() {
        val dump = "Display #20 (activities from top to bottom):\n  * Hist  #0: ActivityRecord{1 u0 a.b/.C} t1}\n"
        assertEquals(emptyList<OpenApp>(), PassengerScreenParsing.appsOn(dump, 2))
    }

    @Test
    fun theRootTaskOfAnAppIsFoundOnAnyDisplayAndNotConfusedWithSimilarPackages() {
        val dump = """
Display #0 (activities from top to bottom):
  * Task{fd43aa1 #114 type=standard A=10186:com.google.android.youtube U=0 visible=true}
    * Hist  #0: ActivityRecord{1 u0 com.google.android.youtube/.X} t114}
  * Task{aa11 #115 type=standard A=10187:com.google.android.youtube.music U=0 visible=false}
Display #2 (activities from top to bottom):
  * Task{bb22 #7 type=standard A=10151:org.triprecorderng U=0 visible=true}
"""
        assertEquals(114, PassengerScreenParsing.rootTaskIdOf(dump, "com.google.android.youtube"))
        assertEquals(115, PassengerScreenParsing.rootTaskIdOf(dump, "com.google.android.youtube.music"))
        assertEquals(7, PassengerScreenParsing.rootTaskIdOf(dump, "org.triprecorderng"))
        assertNull(PassengerScreenParsing.rootTaskIdOf(dump, "com.nothing.here"))
    }

    @Test
    fun onlyPlainPackageNamesAndHttpsLinksAreAccepted() {
        assertTrue(PassengerScreenParsing.isSafePackage("com.google.android.youtube"))
        assertTrue(PassengerScreenParsing.isSafePackage("com.byd.videoplay.fse"))
        for (bad in listOf("", "youtube", "com.a b", "com.a;rm", "com.a/b", "../x", "com.a\$b")) {
            assertFalse("'$bad'", PassengerScreenParsing.isSafePackage(bad))
        }
        assertTrue(PassengerScreenParsing.isSafeUrl("https://www.youtube.com/results?search_query=cats+and+dogs&sp=EgIQAQ%3D%3D"))
        for (bad in listOf("http://x.com", "https://a b", "https://a'b", "https://a\"b", "file:///sdcard", "https://x.com/`id`", "")) {
            assertFalse("'$bad'", PassengerScreenParsing.isSafeUrl(bad))
        }
    }

    @Test
    fun systemWindowsAreNeverClosedButOrdinaryAppsAre() {
        for (system in listOf("android", "com.android.systemui", "com.android.launcher3.fse", "com.android.settings", "com.byd.dicore", "com.xdja.containerservice")) {
            assertFalse(system, PassengerScreenParsing.mayClose(system))
        }
        for (ordinary in listOf("com.google.android.youtube", "com.byd.videoplay.fse", "org.triprecorderng", "com.android.vending")) {
            assertTrue(ordinary, PassengerScreenParsing.mayClose(ordinary))
        }
    }

    @Test
    fun theBrightnessSliderMapsToTheSettingAndBack() {
        assertEquals(10, PassengerScreenParsing.brightnessValue(0))
        assertEquals(255, PassengerScreenParsing.brightnessValue(100))
        assertEquals(133, PassengerScreenParsing.brightnessValue(50))
        assertEquals(0, PassengerScreenParsing.brightnessPercent(10))
        assertEquals(100, PassengerScreenParsing.brightnessPercent(255))
        assertEquals(50, PassengerScreenParsing.brightnessPercent(133))
        // Out-of-range input stays on the slider.
        assertEquals(255, PassengerScreenParsing.brightnessValue(180))
        assertEquals(0, PassengerScreenParsing.brightnessPercent(3))
    }

    @Test
    fun aBrightnessRequestIsAPercentageAStepOrAnEnd() {
        assertEquals(133, PassengerScreenParsing.brightnessTarget("50", current = null))
        assertEquals(133, PassengerScreenParsing.brightnessTarget("50%", current = null))
        assertEquals(255, PassengerScreenParsing.brightnessTarget("max", current = 30))
        assertEquals(10, PassengerScreenParsing.brightnessTarget("MIN", current = 30))
        // One step is a fifth of the slider: from 50 percent up to 70, down to 30.
        assertEquals(PassengerScreenParsing.brightnessValue(70), PassengerScreenParsing.brightnessTarget("up", current = 133))
        assertEquals(PassengerScreenParsing.brightnessValue(30), PassengerScreenParsing.brightnessTarget("down", current = 133))
        // Steps stop at the ends.
        assertEquals(255, PassengerScreenParsing.brightnessTarget("up", current = 250))
        assertEquals(10, PassengerScreenParsing.brightnessTarget("down", current = 12))
        // Anything else is refused, and a step needs a reading to start from.
        assertNull(PassengerScreenParsing.brightnessTarget("101", current = 100))
        assertNull(PassengerScreenParsing.brightnessTarget("-5", current = 100))
        assertNull(PassengerScreenParsing.brightnessTarget("a bit", current = 100))
        assertNull(PassengerScreenParsing.brightnessTarget("up", current = null))
        assertNull(PassengerScreenParsing.brightnessTarget(null, current = 100))
    }
}

package com.bydassistantng.apps

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseAppToolTest {
    private val own = "com.bydassistantng"

    @Test
    fun anAppTheUserInstalledMayBeClosed() {
        assertTrue(CloseAppTool.mayClose("com.google.android.youtube", own, userApp = true))
        assertTrue(CloseAppTool.mayClose("ru.yandex.yandexnavi", own, userApp = true))
        assertTrue(CloseAppTool.mayClose("com.spotify.music", own, userApp = true))
    }

    @Test
    fun aBuiltInAppIsNeverClosed() {
        // The car's settings app has no entry in the system list below, but it is built in: it carries services.
        assertFalse(CloseAppTool.mayClose("com.byd.carsettings", own, userApp = false))
        assertFalse(CloseAppTool.mayClose("com.byd.mediacenter", own, userApp = false))
    }

    @Test
    fun theCarsSystemAndThisAppAreNeverClosedEvenIfTheyLookLikeUserApps() {
        assertFalse(CloseAppTool.mayClose("com.android.systemui", own, userApp = true))
        assertFalse(CloseAppTool.mayClose("com.android.launcher3", own, userApp = true))
        assertFalse(CloseAppTool.mayClose("com.byd.dicore", own, userApp = true))
        assertFalse(CloseAppTool.mayClose("com.xdja.containerservice", own, userApp = true))
        assertFalse(CloseAppTool.mayClose("com.bydassistantng", own, userApp = true))
        assertFalse(CloseAppTool.mayClose("com.bydassistantng.dev", "com.bydassistantng.dev", userApp = true))
        assertFalse(CloseAppTool.mayClose("com.bydassistantng.dev", own, userApp = true))
    }

    @Test
    fun aNameThatIsNotAPackageIsNotClosed() {
        assertFalse(CloseAppTool.mayClose("youtube; reboot", own, userApp = true))
    }

    @Test
    fun anUpdatedBuiltInAppCountsAsTheUsers() {
        assertTrue(CloseAppTool.isUserApp(0))
        assertFalse(CloseAppTool.isUserApp(ApplicationInfo.FLAG_SYSTEM))
        assertTrue(CloseAppTool.isUserApp(ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
    }

    @Test
    fun theToolIsCalledCloseApp() {
        assertEquals("close_app", CloseAppTool.FUNCTION_NAME)
        assertEquals(listOf("name"), CloseAppTool.declaration.parameters!!.required)
    }
}

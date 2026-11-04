package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class PermissionRefusalTest {
    /** What the log of a DiLink 5.0 car showed, word for word. */
    private val bodywork = SecurityException("Neither user 2000 nor current process has android.permission.BYDAUTO_BODYWORK_COMMON.")

    @Test
    fun theMissingPermissionIsReadFromTheMessage() {
        assertEquals("BYDAUTO_BODYWORK_COMMON", PermissionRefusal.missingPermission(bodywork))
        assertEquals(
            "BYDAUTO_SETTING_COMMON",
            PermissionRefusal.missingPermission(SecurityException("Neither user 2000 nor current process has android.permission.BYDAUTO_SETTING_COMMON.")),
        )
    }

    @Test
    fun itIsFoundThroughTheWrapperAReflectiveCallPutsAroundItAndThroughACause() {
        assertEquals("BYDAUTO_BODYWORK_COMMON", PermissionRefusal.missingPermission(InvocationTargetException(bodywork)))
        assertEquals("BYDAUTO_BODYWORK_COMMON", PermissionRefusal.missingPermission(IllegalStateException("outer", bodywork)))
        assertEquals("BYDAUTO_BODYWORK_COMMON", PermissionRefusal.missingPermission(RuntimeException(InvocationTargetException(bodywork))))
    }

    @Test
    fun anythingElseIsNotTheCarsPermissionCheckRefusing() {
        assertNull(PermissionRefusal.missingPermission(null))
        assertNull(PermissionRefusal.missingPermission(IllegalStateException("Neither user 2000 nor current process has android.permission.BYDAUTO_BODYWORK_COMMON.")))
        // The car's own native refusal has no permission in it; it is not this.
        assertNull(PermissionRefusal.missingPermission(SecurityException("[getInt] permission deny!")))
        // Another permission is the system's business, not the car's.
        assertNull(PermissionRefusal.missingPermission(SecurityException("Neither user 2000 nor current process has android.permission.CAMERA.")))
        assertNull(PermissionRefusal.missingPermission(InvocationTargetException(null)))
    }

    @Test
    fun aCauseThatLoopsBackOnItselfIsNotFollowedForever() {
        val loop = RuntimeException("a")
        loop.initCause(RuntimeException("b", loop))
        assertNull(PermissionRefusal.missingPermission(loop))
    }

    @Test
    fun aRefusalIsWordedByThePartOfTheCarWithoutTheNameOfAPermission() {
        assertEquals(
            "This car's software does not let this app use the windows, doors and sunroof. Nothing was sent.",
            PermissionRefusal.message("BYDAUTO_BODYWORK_COMMON", wasCommand = true),
        )
        assertTrue("seats" in PermissionRefusal.message("BYDAUTO_SETTING_COMMON", wasCommand = true))
        assertTrue("climate" in PermissionRefusal.message("BYDAUTO_AC_COMMON", wasCommand = true))
        assertTrue("instrument" in PermissionRefusal.message("BYDAUTO_INSTRUMENT_COMMON", wasCommand = true))
        for (permission in listOf("BYDAUTO_BODYWORK_COMMON", "BYDAUTO_SETTING_COMMON", "BYDAUTO_AC_COMMON", "BYDAUTO_INSTRUMENT_COMMON")) {
            assertFalse(permission, "BYDAUTO" in PermissionRefusal.message(permission, wasCommand = true))
        }
    }

    @Test
    fun aPartOfTheCarThatIsNotKnownKeepsThePermissionSoTheLogSaysWhatRefused() {
        assertEquals(
            "This car's software does not let this app use that part of the car (BYDAUTO_PANORAMA_COMMON). Nothing was sent.",
            PermissionRefusal.message("BYDAUTO_PANORAMA_COMMON", wasCommand = true),
        )
    }

    @Test
    fun aQuestionThatWasRefusedDoesNotSayNothingWasSent() {
        assertEquals(
            "This car's software does not let this app use the instrument readings.",
            PermissionRefusal.message("BYDAUTO_INSTRUMENT_COMMON", wasCommand = false),
        )
    }

    @Test
    fun theProtectionLevelsAreWrittenAsLettersWithTheFlagsThatDecideHowToGrantThem() {
        assertEquals("n", PermissionRefusal.levelCode(0))
        assertEquals("d", PermissionRefusal.levelCode(1))
        assertEquals("s", PermissionRefusal.levelCode(2))
        assertEquals("S", PermissionRefusal.levelCode(3))
        assertEquals("sp", PermissionRefusal.levelCode(2 or 0x10))
        assertEquals("sv", PermissionRefusal.levelCode(2 or 0x20))
        assertEquals("spv", PermissionRefusal.levelCode(2 or 0x10 or 0x20))
        // The flags that are not about granting are left out.
        assertEquals("d", PermissionRefusal.levelCode(1 or 0x40 or 0x80))
    }

    @Test
    fun theReportIsAboutThreeKindsOfPermissionForEachDeviceTheAppReaches() {
        val names = PermissionRefusal.permissionNames()

        assertEquals(21, names.size)
        assertTrue(names.all { it.startsWith("android.permission.BYDAUTO_") })
        assertTrue("android.permission.BYDAUTO_BODYWORK_COMMON" in names)
        assertTrue("android.permission.BYDAUTO_TYRE_GET" in names)
        assertEquals(names.size, names.toSet().size)
    }
}

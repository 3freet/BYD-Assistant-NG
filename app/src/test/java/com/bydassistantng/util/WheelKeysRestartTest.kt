package com.bydassistantng.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DiLink 3.0 restart that frees the wheel button service (see [WheelKeys.ensureServiceRunning]): who gets it, when, and how. */
class WheelKeysRestartTest {

    @Test
    fun aDiLink30UnitIsKnownByItsOwnName() {
        // The unit in the owner's log (2026-11-26), whose numbers read as DiLink 4.
        assertTrue(WheelKeys.isDiLink30("DiLink3.0", "DiLink3.0 For BYD AUTO", "Di3.0_3.5UI"))
        assertTrue(WheelKeys.isDiLink30("DiLink3.0", null, null))
        assertTrue(WheelKeys.isDiLink30(null, "DiLink3.0_For_BYD_AUTO", null))
        assertTrue(WheelKeys.isDiLink30("", "", "Di3.0_3.5UI"))
        // One that runs the 5.0 interface still names itself DiLink3.0.
        assertTrue(WheelKeys.isDiLink30("DiLink3.0", "DiLink3.0 For BYD AUTO", "DiLink50_5.0UI"))
    }

    @Test
    fun noOtherUnitIsTakenForOne() {
        // The Leopard 5 (DiLink 5.1).
        assertFalse(WheelKeys.isDiLink30("DiLink5.1", "DiLink5.1", "Di5.1_5.0UI"))
        assertFalse(WheelKeys.isDiLink30("DiLink4.0", "DiLink4.0", "Di4.0_4.0UI"))
        // An emulator.
        assertFalse(WheelKeys.isDiLink30("generic_arm64", "Android SDK built for arm64", ""))
        // Names that only begin alike.
        assertFalse(WheelKeys.isDiLink30("DiLink3.01", "DiLink30", "Di3.05"))
        assertFalse(WheelKeys.isDiLink30(null, null, null))
    }

    @Test
    fun theRestartIsHeldBackWhenItWouldHurtOrRepeat() {
        val tenMinutes = 10 * 60_000L
        val now = 50 * 60_000L
        assertEquals(WheelKeys.RestartDecision.RESTART, WheelKeys.restartDecision(now, 0L, adbRefused = false, appOnScreen = false))
        assertEquals(WheelKeys.RestartDecision.ON_SCREEN, WheelKeys.restartDecision(now, 0L, adbRefused = false, appOnScreen = true))
        assertEquals(WheelKeys.RestartDecision.ADB_REFUSED, WheelKeys.restartDecision(now, 0L, adbRefused = true, appOnScreen = false))
        assertEquals(WheelKeys.RestartDecision.TOO_SOON, WheelKeys.restartDecision(now, now - tenMinutes + 1, adbRefused = false, appOnScreen = false))
        assertEquals(WheelKeys.RestartDecision.RESTART, WheelKeys.restartDecision(now, now - tenMinutes, adbRefused = false, appOnScreen = false))
        // A time ahead of now was written before a real reboot, which resets the clock: no reason to wait.
        assertEquals(WheelKeys.RestartDecision.RESTART, WheelKeys.restartDecision(1_000L, now, adbRefused = false, appOnScreen = false))
    }

    @Test
    fun theRestartCommandStopsTheAppAndStartsItAgainFromASessionOfItsOwn() {
        val command = WheelKeys.restartCommand("com.bydassistantng")
        assertTrue(command, command.startsWith("setsid sh -c '"))
        assertTrue(command, command.endsWith("&"))
        assertTrue(command, "am force-stop com.bydassistantng;" in command)
        assertTrue(
            command,
            "am broadcast -f 0x20 -a ${WheelKeys.ACTION_RESTARTED} -n com.bydassistantng/com.bydassistantng.service.BootReceiver" in command,
        )
        // A pause first (the ADB call returns before the force-stop cuts it), then the force-stop, then the broadcast.
        val pause = command.indexOf("sleep 1")
        val stop = command.indexOf("am force-stop")
        val start = command.indexOf("am broadcast")
        assertTrue(command, pause in 0 until stop && stop < start)
        // One quoted script, with no quote inside it.
        assertEquals(command, 2, command.count { it == '\'' })
    }
}

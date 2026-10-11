package com.bydassistantng.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExteriorSpeakerSwitchTest {
    @Test
    fun theFeatureIsTheOneTheCarCatalogNames() {
        assertEquals(0xAA000282L.toInt(), ExteriorSpeakerSwitch.FEATURE_ID)
        assertEquals(1002, ExteriorSpeakerSwitch.DEVICE_TYPE)
        assertEquals(0x4FD0002DL.toInt(), ExteriorSpeakerSwitch.MUTE_STATE_ID)
    }

    @Test
    fun onlyTheOneFeatureOnTheAudioDeviceWithZeroOrOneIsPermitted() {
        val device = ExteriorSpeakerSwitch.DEVICE_TYPE
        val feature = ExteriorSpeakerSwitch.FEATURE_ID
        assertTrue(ExteriorSpeakerSwitch.isPermitted(device, feature, 0))
        assertTrue(ExteriorSpeakerSwitch.isPermitted(device, feature, 1))

        assertFalse(ExteriorSpeakerSwitch.isPermitted(device, feature, 2))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(device, feature, -1))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(device, feature, 255))
        // The neighbouring ADAS ids and other devices stay out of reach.
        assertFalse(ExteriorSpeakerSwitch.isPermitted(device, 0xAA000283L.toInt(), 1))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(device, 0xAA000321L.toInt(), 1))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(device, ExteriorSpeakerSwitch.MUTE_STATE_ID, 1))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(1001, feature, 1))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(1000, feature, 0))
    }
}

class ExteriorSpeakerGuardTest {
    private val onSince = 10_000L

    @Test
    fun nothingToDoWhileOff() {
        assertNull(ExteriorSpeakerGuard.mustSwitchOff(on = false, nowMs = 999_999, onSinceMs = 0, lastHeartbeatMs = 0))
    }

    @Test
    fun aHeldSwitchStaysOn() {
        assertNull(ExteriorSpeakerGuard.mustSwitchOff(on = true, nowMs = onSince + 3_000, onSinceMs = onSince, lastHeartbeatMs = onSince + 2_500))
    }

    @Test
    fun aMissingHeartbeatSwitchesItOff() {
        val late = onSince + ExteriorSpeakerGuard.HEARTBEAT_TIMEOUT_MS + 1
        assertEquals(
            ExteriorSpeakerGuard.Reason.NO_HEARTBEAT,
            ExteriorSpeakerGuard.mustSwitchOff(on = true, nowMs = late, onSinceMs = onSince, lastHeartbeatMs = onSince),
        )
    }

    @Test
    fun theHardLimitAppliesEvenWithAHeartbeat() {
        val now = onSince + ExteriorSpeakerGuard.MAX_ON_MS + 1
        assertEquals(
            ExteriorSpeakerGuard.Reason.TOO_LONG,
            ExteriorSpeakerGuard.mustSwitchOff(on = true, nowMs = now, onSinceMs = onSince, lastHeartbeatMs = now - 100),
        )
    }
}

class ExteriorSpeakerSwitchCarTest {
    @Test
    fun withoutTheCarsFrameworkTheExteriorSpeakerIsNotThere() {
        assertFalse(ExteriorSpeakerSwitch.isOnThisCar())
    }
}

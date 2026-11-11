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
        // The neighbouring ADAS ids and other devices stay out of reach (and the media route's feature, where that route is off).
        assertFalse(ExteriorSpeakerSwitch.isPermitted(device, 0xAA000283L.toInt(), 1, mediaRoute = false))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(device, 0xAA000321L.toInt(), 1, mediaRoute = false))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(1000, 0xAA000321L.toInt(), 1, mediaRoute = false))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(device, ExteriorSpeakerSwitch.MUTE_STATE_ID, 1, mediaRoute = false))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(1001, feature, 1, mediaRoute = false))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(1000, feature, 0, mediaRoute = false))
    }

    @Test
    fun theMediaRouteIsPermittedOnlyWhereItIsOnAndOnlyForItsOneFeatureWithZeroOrOne() {
        val media = ExteriorSpeakerSwitch.MEDIA_FEATURE_ID
        assertEquals(0xAA000321L.toInt(), media)

        for (device in ExteriorSpeakerSwitch.MEDIA_DEVICE_TYPES) {
            assertTrue(ExteriorSpeakerSwitch.isPermitted(device, media, 0, mediaRoute = true))
            assertTrue(ExteriorSpeakerSwitch.isPermitted(device, media, 1, mediaRoute = true))
            assertFalse("where the route is off", ExteriorSpeakerSwitch.isPermitted(device, media, 1, mediaRoute = false))
            assertFalse(ExteriorSpeakerSwitch.isPermitted(device, media, 2, mediaRoute = true))
            assertFalse(ExteriorSpeakerSwitch.isPermitted(device, media, -1, mediaRoute = true))
        }
        // No other device type, and nothing next to it, comes with it.
        assertFalse(ExteriorSpeakerSwitch.isPermitted(1001, media, 1, mediaRoute = true))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(1002, 0xAA000283L.toInt(), 1, mediaRoute = true))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(1002, 0xAA000320L.toInt(), 1, mediaRoute = true))
        assertFalse(ExteriorSpeakerSwitch.isPermitted(1002, ExteriorSpeakerSwitch.MUTE_STATE_ID, 1, mediaRoute = true))
    }

    @Test
    fun theMediaRouteIsInEveryBuild() {
        assertTrue(ExteriorSpeakerSwitch.MEDIA_ROUTE_ENABLED)
        val media = ExteriorSpeakerSwitch.MEDIA_FEATURE_ID
        assertTrue(ExteriorSpeakerSwitch.isPermitted(ExteriorSpeakerSwitch.DEVICE_TYPE, media, 1))
        assertTrue(ExteriorSpeakerSwitch.isPermitted(ExteriorSpeakerSwitch.DEVICE_TYPE, media, 0))
        assertFalse("still only 0 and 1", ExteriorSpeakerSwitch.isPermitted(ExteriorSpeakerSwitch.DEVICE_TYPE, media, 2))
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

    @Test
    fun withoutTheCarsFrameworkTheMediaRouteIsNotThereEither() {
        assertFalse(ExteriorSpeakerSwitch.isMediaRouteOnThisCar())
    }
}

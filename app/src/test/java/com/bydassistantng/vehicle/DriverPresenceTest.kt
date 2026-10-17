package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverPresenceTest {
    @Test
    fun readsBackWhatItEncoded() {
        val state = DriverPresence.State(seat = DriverPresence.SEAT_OCCUPIED, door = DriverPresence.DOOR_SHUT)
        assertEquals(state, DriverPresence.parse(DriverPresence.encode(state)))
    }

    @Test
    fun aPartTheCarDidNotReportStaysNull() {
        val state = DriverPresence.State(seat = null, door = DriverPresence.DOOR_OPEN)
        assertEquals("seat=-1 door=1", DriverPresence.encode(state))
        assertEquals(state, DriverPresence.parse("seat=-1 door=1"))
    }

    @Test
    fun garbageIsNotAReading() {
        assertNull(DriverPresence.parse(null))
        assertNull(DriverPresence.parse(""))
        assertNull(DriverPresence.parse("seat=2"))
        assertNull(DriverPresence.parse("mode=3 speakers=2"))
    }

    @Test
    fun twoIsOccupiedAndOneIsEmpty() {
        assertTrue(DriverPresence.State(2, 0).seated)
        assertFalse(DriverPresence.State(1, 0).seated)
        assertFalse(DriverPresence.State(null, 0).seated)
    }

    @Test
    fun readyNeedsTheDriverSeatedAndTheDoorReadShut() {
        assertTrue(DriverPresence.State(2, 0).ready)
        assertFalse(DriverPresence.State(2, 1).ready)
        assertFalse(DriverPresence.State(1, 0).ready)
        // No reading for the door is not a shut door.
        assertFalse(DriverPresence.State(2, null).ready)
    }

    @Test
    fun theQueryHasItsOwnWordForTheHelper() {
        assertEquals(VehicleQuery.DRIVER_PRESENCE, VehicleQuery.byId("driver_presence"))
    }
}

package com.bydassistantng.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading where the car is from Android's location report, as the Car Link does, and passing it back to the app. */
class CarLinkLocationTest {
    // The part of `dumpsys location` on Android 10 that holds the last locations (an emulator's, the position made up).
    private val android10Report = """
        Location Manager State:
          Last Known Locations:
            gps: Location[gps 51.500729,-0.124625 hAcc=20 et=+1m59s561ms alt=0.0 vel=0.0 bear=0.0 vAcc=??? sAcc=??? bAcc=??? {Bundle[{satellites=0, maxCn0=0, meanCn0=0}]}]
            passive: Location[gps 51.500729,-0.124625 hAcc=20 et=+1m59s561ms alt=0.0 vel=0.0 bear=0.0 vAcc=??? sAcc=??? bAcc=??? {Bundle[{satellites=0, maxCn0=0, meanCn0=0}]}]
          Last Known Locations Coarse Intervals:
    """.trimIndent()

    @Test
    fun theNewestLocationIsFoundWithItsAge() {
        // Two minutes after boot, a fix taken at 1 min 59.561 s is 439 ms old.
        val position = CarLinkLocation.newest(android10Report, nowElapsedMs = 120_000)!!
        assertEquals(51.500729, position.latitude, 1e-9)
        assertEquals(-0.124625, position.longitude, 1e-9)
        assertEquals(439L, position.ageMs)
        assertEquals("51.500729,-0.124625", position.destination)
    }

    @Test
    fun ofSeveralTheMostRecentIsTaken() {
        val report = """
            network: Location[network 1.000000,2.000000 hAcc=500 et=+10m0s0ms]
            gps: Location[gps 3.000000,4.000000 hAcc=5 et=+1h2m3s4ms alt=0.0]
            fused: Location[fused 5.000000,6.000000 hAcc=10 et=+30s0ms]
        """.trimIndent()
        val position = CarLinkLocation.newest(report, nowElapsedMs = 3_723_004 + 1_000)!!
        assertEquals(3.0, position.latitude, 1e-9)
        assertEquals(1_000L, position.ageMs)
        assertEquals("car link gps", position.source)
    }

    @Test
    fun aReportWithNoLocationGivesNone() {
        assertNull(CarLinkLocation.newest("Location Manager State:\n  Last Known Locations:\n", 120_000))
        // A location without a time since boot cannot be aged, so it is not used.
        assertNull(CarLinkLocation.newest("gps: Location[gps 1.0,2.0 hAcc=5 et=?!? alt=0.0]", 120_000))
    }

    @Test
    fun androidDurationsAreReadInMilliseconds() {
        assertEquals(119_561L, CarLinkLocation.durationMs("1m59s561ms"))
        assertEquals(3_723_004L, CarLinkLocation.durationMs("1h2m3s4ms"))
        assertEquals(90_000_000L, CarLinkLocation.durationMs("1d1h"))
        assertEquals(500L, CarLinkLocation.durationMs("500ms"))
        assertNull(CarLinkLocation.durationMs("?!?"))
    }

    @Test
    fun thePositionGoesFromTheHelperToTheAppAndBack() {
        val position = CarPosition(51.500729, -0.124625, 1_234, "car link gps")
        assertEquals("51.500729,-0.124625,1234,car link gps", CarLinkLocation.encode(position))
        assertEquals(position, CarLinkLocation.decode(CarLinkLocation.encode(position)))
        assertNull(CarLinkLocation.decode(null))
        assertNull(CarLinkLocation.decode("not a position"))
    }
}

package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveModeControlTest {
    private val device = DriveModeControl.DEVICE_TYPE
    private val feature = DriveModeControl.PRESELECT_ID

    @Test
    fun theFeatureIsTheOneTheCarsOwnScreenLogged() {
        // "setValue ----> device = BYDAutoSettingDevice featureId : 4c123030 value : 5" on the car, 2026-10-11.
        assertEquals(0x4C123030, feature)
        assertEquals(1276260400, feature)
        assertEquals(1023, device)
    }

    @Test
    fun theNumbersAreTheOnesTheCarsOwnScreenSends() {
        assertEquals(4, DriveModeControl.requestFor(DriveMode.SNOW))
        assertEquals(5, DriveModeControl.requestFor(DriveMode.SAND))
        assertEquals(6, DriveModeControl.requestFor(DriveMode.MUD))
        assertEquals(7, DriveModeControl.requestFor(DriveMode.MOUNTAIN))
        assertEquals(8, DriveModeControl.requestFor(DriveMode.ROCK))
        assertEquals(21, DriveModeControl.requestFor(DriveMode.AUTO))
        assertEquals(255, DriveModeControl.requestFor(DriveMode.EXIT))
        // The three road modes are an experiment: the car's own screen never writes them.
        assertEquals(listOf(1, 2, 3), listOf(DriveMode.NORMAL, DriveMode.ECO, DriveMode.SPORT).map(DriveModeControl::requestFor))
    }

    @Test
    fun everyModeTheModelCanNameIsPermittedAndNothingElseIs() {
        for (mode in DriveMode.entries) {
            assertTrue(mode.word, DriveModeControl.isPermitted(device, feature, DriveModeControl.requestFor(mode)))
        }
        // Wade, warp, custom, traction, super wading, VOT, u-turn, and values that mean nothing.
        for (value in listOf(0, 9, 10, 12, 19, 20, 23, 24, 256, -1, 65535)) {
            assertFalse("value $value", DriveModeControl.isPermitted(device, feature, value))
        }
    }

    @Test
    fun onlyThatFeatureOnThatDeviceIsPermitted() {
        assertFalse(DriveModeControl.isPermitted(device, 0x4C123031, 5))
        assertFalse(DriveModeControl.isPermitted(device, 0x0F4000A8, 5)) // the state read back, not the control
        assertFalse(DriveModeControl.isPermitted(1001, feature, 5))
        assertFalse(DriveModeControl.isPermitted(1002, feature, 5))
    }

    @Test
    fun theCarMustBeBelowTheSpeedLimitAndAnUnknownSpeedIsNotOk() {
        assertEquals(80.0, DriveModeControl.MAX_SPEED_KMH, 0.0)
        assertTrue(DriveModeControl.mayChangeAt(0.0))
        assertTrue(DriveModeControl.mayChangeAt(50.0))
        assertTrue(DriveModeControl.mayChangeAt(79.9))
        assertFalse(DriveModeControl.mayChangeAt(80.0))
        assertFalse(DriveModeControl.mayChangeAt(120.0))
        assertFalse(DriveModeControl.mayChangeAt(-1.0))
        assertFalse(DriveModeControl.mayChangeAt(Double.NaN))
        assertFalse(DriveModeControl.mayChangeAt(null))
    }

    @Test
    fun modesHaveTheCarsOwnNames() {
        assertEquals("Sand", DriveModeControl.nameOf(5))
        assertEquals("Normal", DriveModeControl.nameOf(1))
        assertEquals("Auto", DriveModeControl.nameOf(21))
        assertEquals("mode 77", DriveModeControl.nameOf(77))
    }

    @Test
    fun theHelpersNoteRoundTrips() {
        val reading = DriveModeControl.Reading(before = 1, now = 5, requested = 5, ms = 140, speedKmh = 0)
        assertEquals(reading, DriveModeControl.parseNote(DriveModeControl.note(reading)))
        assertNull(DriveModeControl.parseNote("garbage"))
        assertNull(DriveModeControl.parseNote("before=1 now=5"))
    }

    private fun success(before: Int, now: Int, requested: Int) = VehicleDispatchResult.Success(
        DriveModeControl.note(DriveModeControl.Reading(before, now, requested, ms = 120, speedKmh = 0)),
    )

    @Test
    fun aCarThatReportsTheRequestedModeHasChanged() {
        assertEquals(
            DriveModeOutcome.Changed(before = "Normal", now = "Sand"),
            DriveModeControl.outcomeOf(DriveMode.SAND, success(before = 1, now = 5, requested = 5)),
        )
    }

    @Test
    fun aCarThatStayedOrWentElsewhereHasNotChanged() {
        assertEquals(
            DriveModeOutcome.Unchanged(current = "Normal", detail = "it stayed as it was"),
            DriveModeControl.outcomeOf(DriveMode.MUD, success(before = 1, now = 1, requested = 6)),
        )
        assertEquals(
            DriveModeOutcome.Unchanged(current = "Rock", detail = "it went to Rock instead"),
            DriveModeControl.outcomeOf(DriveMode.MUD, success(before = 1, now = 8, requested = 6)),
        )
    }

    @Test
    fun exitIsReachedWhenTheCarLeavesTheOffRoadModeOrWasNeverIn() {
        // The car's mode after an exit is whatever road mode it goes back to, not 255.
        assertTrue(DriveModeControl.outcomeOf(DriveMode.EXIT, success(before = 5, now = 1, requested = 255)) is DriveModeOutcome.Changed)
        assertTrue(DriveModeControl.outcomeOf(DriveMode.EXIT, success(before = 2, now = 2, requested = 255)) is DriveModeOutcome.Changed)
        assertTrue(DriveModeControl.outcomeOf(DriveMode.EXIT, success(before = 5, now = 5, requested = 255)) is DriveModeOutcome.Unchanged)
    }

    @Test
    fun refusalsAndLostLinksAreNeverReportedAsChanges() {
        assertTrue(DriveModeControl.outcomeOf(DriveMode.SAND, null) is DriveModeOutcome.Refused)
        val moving = VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, "the car is not stationary (speed 40 km/h)")
        assertEquals(
            DriveModeOutcome.Refused("the car is not stationary (speed 40 km/h)"),
            DriveModeControl.outcomeOf(DriveMode.SAND, moving),
        )
        assertTrue(DriveModeControl.outcomeOf(DriveMode.SAND, VehicleDispatchResult.Success("nonsense")) is DriveModeOutcome.Refused)
        assertTrue(DriveModeControl.outcomeOf(DriveMode.SAND, VehicleDispatchResult.Blocked("no")) is DriveModeOutcome.Refused)
    }
}

class DriveModeCommandTest {
    @Test
    fun theHelperRoutesTheCommandByThisName() {
        assertEquals("drivemode", DriveModeCommand.NAME)
    }

    @Test
    fun aValueTheCarsScreenNeverSendsIsRefusedBeforeTheCarIsTouched() {
        for (value in listOf("9", "20", "19", "23", "0", "-1", "256", "abc")) {
            val answer = DriveModeCommand.run(listOf("set", value))
            assertTrue("value $value: $answer", answer is VehicleDispatchResult.Failure)
            assertEquals(VehicleDispatchError.INVALID_ARGUMENT, (answer as VehicleDispatchResult.Failure).error)
        }
    }

    @Test
    fun aMalformedCommandIsRefused() {
        for (args in listOf(emptyList(), listOf("get", "5"), listOf("set"), listOf("set", "5", "6"))) {
            val answer = DriveModeCommand.run(args)
            assertEquals(VehicleDispatchError.INVALID_ARGUMENT, (answer as VehicleDispatchResult.Failure).error)
        }
    }
}

class CarFeatureIdsTest {
    @Test
    fun withoutTheCarsFrameworkNothingIsKnownAndNothingIsOfferedOrSent() {
        // A phone or a unit test has no BYDAutoFeatureIds: the features must not look present there.
        assertNull(CarFeatureIds.idOf("Setting", DriveModeControl.PRESELECT_NAME))
        assertFalse(DriveModeControl.isOnThisCar())
    }
}

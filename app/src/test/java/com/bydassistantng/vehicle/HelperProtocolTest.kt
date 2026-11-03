package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelperProtocolTest {
    private fun roundTrip(result: VehicleDispatchResult) = HelperProtocol.parse(HelperProtocol.encode(result))

    @Test
    fun successRoundTrips() {
        assertEquals(VehicleDispatchResult.Success(), roundTrip(VehicleDispatchResult.Success()))
        assertEquals(VehicleDispatchResult.Success("accepted"), roundTrip(VehicleDispatchResult.Success("accepted")))
    }

    @Test
    fun blockedRoundTrips() {
        assertEquals(VehicleDispatchResult.Blocked("domain denied"), roundTrip(VehicleDispatchResult.Blocked("domain denied")))
    }

    @Test
    fun everyFailureKindRoundTrips() {
        for (error in VehicleDispatchError.entries) {
            assertEquals(VehicleDispatchResult.Failure(error, "boom"), roundTrip(VehicleDispatchResult.Failure(error, "boom")))
            assertEquals(VehicleDispatchResult.Failure(error, null), roundTrip(VehicleDispatchResult.Failure(error, null)))
        }
    }

    @Test
    fun detailNewlinesAreFlattenedSoAResultIsOneLine() {
        val line = HelperProtocol.encode(VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, "first\nsecond\n\tthird"))
        assertEquals(1, line.lines().size)
        assertEquals(VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, "first second third"), HelperProtocol.parse(line))
    }

    @Test
    fun resultIsFoundAmongRuntimeNoise() {
        val output = "WARNING: linker: something\n${HelperProtocol.encode(VehicleDispatchResult.Success())}\nsome trailing text\n"
        assertEquals(VehicleDispatchResult.Success(), HelperProtocol.parse(output))
    }

    @Test
    fun noResultLineMeansNull() {
        assertNull(HelperProtocol.parse(""))
        assertNull(HelperProtocol.parse("Exception in thread main\n\tat foo"))
    }

    @Test
    fun anUnknownErrorNameDegradesToUnknown() {
        assertEquals(
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, "x"),
            HelperProtocol.parse("BYDRESULT FAIL SOMETHING_NEW x"),
        )
    }

    @Test
    fun theReadyLineCarriesThePlatformWhatTheCarCannotDoAndWhatTheHelperSaw() {
        val line = HelperProtocol.ready(DiLinkPlatform.DILINK_3, setOf("window.driver.position", "fridge.mode"), "sdk=29\nrelease=10")

        assertEquals(1, line.lines().size)
        assertEquals("BYDREADY dilink3 unavailable=fridge.mode,window.driver.position sdk=29 release=10", line)
        assertEquals(
            HelperProtocol.Ready(DiLinkPlatform.DILINK_3, setOf("fridge.mode", "window.driver.position"), "sdk=29 release=10"),
            HelperProtocol.parseReady(line),
        )
    }

    @Test
    fun aCarThatCanDoEverythingSaysNoneAndTheDetailsStillComeThrough() {
        val line = HelperProtocol.ready(DiLinkPlatform.DILINK_5, emptySet(), "sdk=33")

        assertEquals("BYDREADY dilink5 unavailable=- sdk=33", line)
        assertEquals(HelperProtocol.Ready(DiLinkPlatform.DILINK_5, emptySet(), "sdk=33"), HelperProtocol.parseReady(line))
    }

    @Test
    fun aReadyLineWithNoDetailsAtAllIsStillRead() {
        val line = HelperProtocol.ready(DiLinkPlatform.DILINK_5, setOf("fridge.mode"), "")

        assertEquals(HelperProtocol.Ready(DiLinkPlatform.DILINK_5, setOf("fridge.mode"), ""), HelperProtocol.parseReady(line))
    }

    @Test
    fun aReadyLineFromAHelperThatDoesNotSayWhatTheCarCannotDoMeansNothingIsKnown() {
        assertEquals(HelperProtocol.Ready(DiLinkPlatform.DILINK_3, emptySet(), "sdk=29"), HelperProtocol.parseReady("BYDREADY dilink3 sdk=29"))
    }

    @Test
    fun aBareReadyLineIsAnUnrecognisedPlatform() {
        assertEquals(HelperProtocol.Ready(DiLinkPlatform.UNKNOWN, emptySet(), ""), HelperProtocol.parseReady(HelperProtocol.READY_LINE))
    }

    @Test
    fun anUnrecognisedPlatformStillCarriesWhatTheCarCannotDoAndItsDetails() {
        val line = HelperProtocol.ready(DiLinkPlatform.UNKNOWN, setOf("sunroof"), "sdk=29")

        assertEquals(HelperProtocol.Ready(DiLinkPlatform.UNKNOWN, setOf("sunroof"), "sdk=29"), HelperProtocol.parseReady(line))
    }

    @Test
    fun aPlatformKeyFromTheFutureIsNotMistakenForOne() {
        assertEquals(HelperProtocol.Ready(DiLinkPlatform.UNKNOWN, emptySet(), "dilink9 sdk=40"), HelperProtocol.parseReady("BYDREADY dilink9 sdk=40"))
    }

    @Test
    fun otherLinesAreNotReadyLines() {
        assertNull(HelperProtocol.parseReady(""))
        assertNull(HelperProtocol.parseReady("BYDRESULT OK"))
    }
}

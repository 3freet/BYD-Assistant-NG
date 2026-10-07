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
}

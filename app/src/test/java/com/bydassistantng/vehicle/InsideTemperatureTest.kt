package com.bydassistantng.vehicle

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsideTemperatureTest {
    @Test
    fun aCelsiusReadingIsReported() {
        val report = InsideTemperatureTool.report(RawInsideTemperature(celsius = 24, unitCode = 1))
        assertEquals("ok", report.getValue("status").jsonPrimitive.content)
        assertEquals("24", report.getValue("celsius").jsonPrimitive.content)
    }

    @Test
    fun noReadingZeroOrAnotherUnitIsNotAvailable() {
        for (reading in listOf(
            RawInsideTemperature(null, 1),
            RawInsideTemperature(0, 1), // the climate system's own "no temperature"
            RawInsideTemperature(120, 1),
            RawInsideTemperature(75, 0), // Fahrenheit: not converted, not guessed
            RawInsideTemperature(24, null),
        )) {
            val report = InsideTemperatureTool.report(reading)
            assertEquals("$reading", "error", report.getValue("status").jsonPrimitive.content)
            assertEquals("NOT_AVAILABLE", report.getValue("error").jsonPrimitive.content)
        }
    }

    @Test
    fun onlyARealCabinTemperatureIsUsable() {
        assertEquals(24, InsideTemperatureTool.usable(24))
        assertNull(InsideTemperatureTool.usable(null))
        assertNull(InsideTemperatureTool.usable(0))
        assertNull(InsideTemperatureTool.usable(91))
    }

    @Test
    fun theReadingSurvivesTheHelperLine() {
        val reading = RawInsideTemperature(celsius = 31, unitCode = 1)
        assertEquals(reading, InsideTemperatureTool.parse(InsideTemperatureTool.encode(reading)))
        assertNull(InsideTemperatureTool.parse("nonsense"))
        assertEquals(VehicleQuery.INSIDE_TEMPERATURE, VehicleQuery.byId("inside_temperature"))
    }
}

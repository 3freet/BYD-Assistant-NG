package com.bydassistantng.vehicle

import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutsideTemperatureTest {
    @Test
    fun aCelsiusReadingIsReported() {
        val report = OutsideTemperatureTool.report(RawOutsideTemperature(celsius = 35, unitCode = 1))
        assertEquals("ok", report.getValue("status").jsonPrimitive.content)
        assertEquals(35, report.getValue("celsius").jsonPrimitive.intOrNull)
    }

    @Test
    fun aNegativeTemperatureIsAReading() {
        val report = OutsideTemperatureTool.report(RawOutsideTemperature(celsius = -12, unitCode = 1))
        assertEquals(-12, report.getValue("celsius").jsonPrimitive.intOrNull)
    }

    @Test
    fun noValueOrAnUnknownUnitIsNotGuessed() {
        for (reading in listOf(RawOutsideTemperature(null, 1), RawOutsideTemperature(35, 2), RawOutsideTemperature(35, null))) {
            val report = OutsideTemperatureTool.report(reading)
            assertEquals("error", report.getValue("status").jsonPrimitive.content)
            assertNull(report["celsius"])
        }
    }

    @Test
    fun theHelpersAnswerSurvivesTheTripThroughText() {
        val reading = RawOutsideTemperature(35, 1)
        assertEquals(reading, OutsideTemperatureTool.parse(OutsideTemperatureTool.encode(reading)))
        assertNull(OutsideTemperatureTool.parse("nonsense"))
        assertEquals(VehicleQuery.OUTSIDE_TEMPERATURE, VehicleQuery.byId("outside_temperature"))
    }
}

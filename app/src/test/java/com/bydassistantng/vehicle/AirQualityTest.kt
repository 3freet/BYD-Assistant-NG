package com.bydassistantng.vehicle

import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirQualityTest {
    @Test
    fun insideAndOutsideAreReportedWithTheCarsRatings() {
        val report = AirQualityTool.report(
            RawAirQuality(insideUgm3 = 7, outsideUgm3 = 14, insideLevel = 1, outsideLevel = 2, online = 1, warning = 0, ionizer = 1),
        )
        assertEquals("ok", report.getValue("status").jsonPrimitive.content)
        assertEquals(7, report.getValue("inside").jsonPrimitive.intOrNull)
        assertEquals(14, report.getValue("outside").jsonPrimitive.intOrNull)
        assertEquals("excellent", report.getValue("inside_rating").jsonPrimitive.content)
        assertEquals("good", report.getValue("outside_rating").jsonPrimitive.content)
        assertEquals("on", report.getValue("air_ionizer").jsonPrimitive.content)
        assertNull(report["warning"])
    }

    @Test
    fun theLevelNamesFollowTheDevicesSixNumbers() {
        assertEquals(
            listOf("excellent", "good", "light_pollution", "moderate_pollution", "heavy_pollution", "serious_pollution"),
            (1..6).map { AirQualityTool.levelName(it) },
        )
        assertNull(AirQualityTool.levelName(0))
        assertNull(AirQualityTool.levelName(null))
    }

    @Test
    fun aWarningNamesTheSideThatIsOverTheLimit() {
        val inside = AirQualityTool.report(RawAirQuality(insideUgm3 = 90, outsideUgm3 = 20, warning = 1))
        val outside = AirQualityTool.report(RawAirQuality(insideUgm3 = 10, outsideUgm3 = 200, warning = 2))
        assertEquals(true, inside.getValue("warning").jsonPrimitive.content.contains("inside"))
        assertEquals(true, outside.getValue("warning").jsonPrimitive.content.contains("outside"))
    }

    @Test
    fun sensorsSwitchedOffOrNoValuesAreNotInvented() {
        val off = AirQualityTool.report(RawAirQuality(insideUgm3 = 7, outsideUgm3 = 14, online = 2))
        assertEquals("error", off.getValue("status").jsonPrimitive.content)
        assertNull(off["inside"])
        val none = AirQualityTool.report(RawAirQuality())
        assertEquals("error", none.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun oneSideAloneIsStillAnAnswer() {
        val report = AirQualityTool.report(RawAirQuality(insideUgm3 = 12, outsideUgm3 = null, online = 1))
        assertEquals("ok", report.getValue("status").jsonPrimitive.content)
        assertEquals(12, report.getValue("inside").jsonPrimitive.intOrNull)
        assertNull(report["outside"])
    }

    @Test
    fun theHelpersAnswerSurvivesTheTripThroughText() {
        val reading = RawAirQuality(7, 14, 1, 2, 1, 0, 1)
        assertEquals(reading, AirQualityTool.parse(AirQualityTool.encode(reading)))
        assertNull(AirQualityTool.parse("nonsense"))
        assertEquals(VehicleQuery.AIR_QUALITY, VehicleQuery.byId("air_quality"))
    }
}

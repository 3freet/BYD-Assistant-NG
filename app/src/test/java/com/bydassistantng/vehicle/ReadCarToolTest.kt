package com.bydassistantng.vehicle

import com.bydassistantng.gemini.GeminiFunctionCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadCarToolTest {
    private fun call(what: String?) = GeminiFunctionCall(
        name = ReadCarTool.FUNCTION_NAME,
        args = if (what == null) JsonObject(emptyMap()) else JsonObject(mapOf("what" to JsonPrimitive(what))),
    )

    @Test
    fun theDeclarationOffersTheThreeReadingsAndRequiresOne() {
        val parameters = checkNotNull(ReadCarTool.declaration.parameters)
        assertEquals(listOf("what"), parameters.required)
        assertEquals(listOf("tyres", "outside_temperature", "air_quality"), parameters.properties?.getValue("what")?.enum)
    }

    @Test
    fun eachWordMapsToItsQuery() {
        assertEquals(VehicleQuery.TYRES, ReadCarTool.what(call("tyres"))?.query)
        assertEquals(VehicleQuery.OUTSIDE_TEMPERATURE, ReadCarTool.what(call(" Outside_Temperature "))?.query)
        assertEquals(VehicleQuery.AIR_QUALITY, ReadCarTool.what(call("air_quality"))?.query)
        assertNull(ReadCarTool.what(call("engine")))
        assertNull(ReadCarTool.what(call(null)))
    }

    @Test
    fun eachAnswerIsReportedByItsOwnReader() {
        val temperature = ReadCarTool.report(ReadCarTool.What.OUTSIDE_TEMPERATURE, OutsideTemperatureTool.encode(RawOutsideTemperature(35, 1)))
        assertEquals("ok", temperature?.getValue("status")?.jsonPrimitive?.content)
        val air = ReadCarTool.report(ReadCarTool.What.AIR_QUALITY, AirQualityTool.encode(RawAirQuality(insideUgm3 = 7, outsideUgm3 = 14, online = 1)))
        assertEquals("ok", air?.getValue("status")?.jsonPrimitive?.content)
        val tyres = ReadCarTool.report(ReadCarTool.What.TYRES, TyreStatusTool.encode(RawTyreSnapshot(monitored = false)))
        assertEquals("error", tyres?.getValue("status")?.jsonPrimitive?.content)
        assertNotNull(tyres?.get("detail"))
    }

    @Test
    fun anAnswerOfTheWrongKindOrNoneCannotBeReported() {
        assertNull(ReadCarTool.report(ReadCarTool.What.TYRES, "nonsense"))
        assertNull(ReadCarTool.report(ReadCarTool.What.AIR_QUALITY, null))
    }
}

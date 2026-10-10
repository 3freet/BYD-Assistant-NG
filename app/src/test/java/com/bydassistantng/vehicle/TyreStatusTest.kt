package com.bydassistantng.vehicle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class TyreStatusTest {
    // What the car answered on the unit this was built on: 107/112/120/117 kPa, all under-pressure, cluster in psi.
    private fun snapshot(vararg wheels: RawWheel, monitored: Boolean = true, unit: Int? = 2, system: Int? = 0) =
        RawTyreSnapshot(monitored = monitored, system = system, clusterPressureUnit = unit, wheels = wheels.toList())

    private fun wheel(w: Wheel, kpa: Int?, state: Int? = 0, leak: Int? = 0, signal: Int? = 0, celsius: Int? = null) =
        RawWheel(w.key, kpa, state, leak, signal, celsius)

    private fun JsonObject.wheelNamed(position: String): JsonObject =
        getValue("wheels").jsonArray.map { it.jsonObject }.first { it.getValue("position").jsonPrimitive.content == position }

    @Test
    fun convertsKilopascalsToBarAndPsi() {
        assertEquals(1.07, TyreStatusTool.bar(107), 1e-9)
        assertEquals(15.5, TyreStatusTool.psi(107), 1e-9)
        assertEquals(16.2, TyreStatusTool.psi(112), 1e-9)
        assertEquals(17.4, TyreStatusTool.psi(120), 1e-9)
        assertEquals(36.2, TyreStatusTool.psi(250), 1e-9)
    }

    @Test
    fun psiIsCutOffLikeTheClusterDoesNotRounded() {
        // 117 kPa is 16.97 psi: the cluster shows 16.9, so the app must not say 17.0.
        assertEquals(16.9, TyreStatusTool.psi(117), 1e-9)
        // The same car a little later: 110 -> 15.95 (cluster 15.9), 115 -> 16.68 (cluster 16.6).
        assertEquals(15.9, TyreStatusTool.psi(110), 1e-9)
        assertEquals(16.6, TyreStatusTool.psi(115), 1e-9)
    }

    @Test
    fun theClustersOwnFigureWinsWhenItShowsPsi() {
        val raw = wheel(Wheel.FRONT_LEFT, 110).copy(cluster = 159)
        assertEquals(15.9, TyreStatusTool.psiShown(raw, TyreStatusTool.PSI_UNIT_CODE, 110), 1e-9)
        // A cluster in another unit is not read as psi.
        assertEquals(15.9, TyreStatusTool.psiShown(raw, 3, 110), 1e-9)
        assertEquals(15.9, TyreStatusTool.psiShown(raw.copy(cluster = null), TyreStatusTool.PSI_UNIT_CODE, 110), 1e-9)
        val other = raw.copy(cluster = 160)
        assertEquals(16.0, TyreStatusTool.psiShown(other, TyreStatusTool.PSI_UNIT_CODE, 110), 1e-9)
    }

    @Test
    fun theClusterNumbersFollowFromTheTyreDeviceOnes() {
        // The cluster shows tenths of psi, cut off rather than rounded: 155, 162, 174, 169 for 107, 112, 120, 117 kPa.
        assertEquals(listOf(155, 162, 174, 169), listOf(107, 112, 120, 117).map { (TyreStatusTool.psi(it) * 10).roundToInt() })
        assertEquals(listOf(159, 166, 174, 169), listOf(110, 115, 120, 117).map { (TyreStatusTool.psi(it) * 10).roundToInt() })
    }

    @Test
    fun theWheelNumberingOfTheTwoDevicesIsNotTheSame() {
        assertEquals(listOf(1, 2, 3, 4), Wheel.entries.map { it.tyreArea })
        assertEquals(listOf(3, 1, 4, 2), Wheel.entries.map { it.clusterArea })
        assertEquals(Wheel.entries.size, Wheel.entries.map { it.key }.toSet().size)
    }

    @Test
    fun reportsEachWheelWithAllThreeUnitsAndTheCarsVerdict() {
        val report = TyreStatusTool.report(
            snapshot(
                wheel(Wheel.FRONT_LEFT, 107, state = 2, celsius = 31),
                wheel(Wheel.FRONT_RIGHT, 112, state = 2),
                wheel(Wheel.REAR_LEFT, 250, state = 0),
                wheel(Wheel.REAR_RIGHT, 117, state = 2),
            ),
        )
        assertEquals("ok", report.getValue("status").jsonPrimitive.content)
        assertEquals("psi", report.getValue("car_display_unit").jsonPrimitive.content)
        val fl = report.wheelNamed("front left")
        assertEquals(107, fl.getValue("kpa").jsonPrimitive.intOrNull)
        assertEquals(1.07, fl.getValue("bar").jsonPrimitive.doubleOrNull!!, 1e-9)
        assertEquals(15.5, fl.getValue("psi").jsonPrimitive.doubleOrNull!!, 1e-9)
        assertEquals("low", fl.getValue("pressure_state").jsonPrimitive.content)
        assertEquals(31, fl.getValue("temperature_celsius").jsonPrimitive.intOrNull)
        assertEquals("normal", report.wheelNamed("rear left").getValue("pressure_state").jsonPrimitive.content)
        assertNull(report.wheelNamed("front right")["temperature_celsius"])
    }

    @Test
    fun warningsNameTheWheelAndWhatIsWrong() {
        val report = TyreStatusTool.report(
            snapshot(
                wheel(Wheel.FRONT_LEFT, 107, state = 2),
                wheel(Wheel.FRONT_RIGHT, 300, state = 1),
                wheel(Wheel.REAR_LEFT, 230, leak = 2),
                wheel(Wheel.REAR_RIGHT, null, signal = 1),
            ),
        )
        val warnings = report.getValue("warnings").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("front left tyre pressure is low" in warnings)
        assertTrue("front right tyre pressure is high" in warnings)
        assertTrue("rear left tyre is losing air slowly" in warnings)
        assertTrue("no signal from the rear right tyre sensor" in warnings)
        assertEquals("not available", report.wheelNamed("rear right").getValue("pressure").jsonPrimitive.content)
    }

    @Test
    fun aHealthyCarHasNoWarnings() {
        val report = TyreStatusTool.report(snapshot(*Wheel.entries.map { wheel(it, 240) }.toTypedArray()))
        assertEquals(JsonArray(emptyList()), report.getValue("warnings"))
    }

    @Test
    fun aCarWithoutAMonitorSaysSo() {
        val report = TyreStatusTool.report(RawTyreSnapshot(monitored = false))
        assertEquals("error", report.getValue("status").jsonPrimitive.content)
        assertEquals("NOT_AVAILABLE", report.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun theDisplayUnitIsOnlyNamedWhenItIsKnown() {
        val unknown = TyreStatusTool.report(snapshot(wheel(Wheel.FRONT_LEFT, 240), unit = 3))
        assertNull(unknown["car_display_unit"])
        val noUnit = TyreStatusTool.report(snapshot(wheel(Wheel.FRONT_LEFT, 240), unit = null))
        assertNull(noUnit["car_display_unit"])
    }

    @Test
    fun aSystemFaultIsMentionedAndANormalSystemIsNot() {
        assertNull(TyreStatusTool.report(snapshot(wheel(Wheel.FRONT_LEFT, 240), system = 0))["system"])
        assertNotNull(TyreStatusTool.report(snapshot(wheel(Wheel.FRONT_LEFT, 240), system = 3))["system"])
    }

    @Test
    fun theHelperSnapshotSurvivesTheTripThroughText() {
        val original = snapshot(wheel(Wheel.FRONT_LEFT, 107, state = 2, celsius = 31), wheel(Wheel.REAR_RIGHT, null, signal = 1))
        assertEquals(original, TyreStatusTool.parse(TyreStatusTool.encode(original)))
        assertNull(TyreStatusTool.parse("not json"))
        assertEquals(VehicleQuery.TYRES, VehicleQuery.byId("tyres"))
        assertNull(VehicleQuery.byId("engine"))
        assertTrue(JsonPrimitive("x").contentOrNull == "x")
    }
}

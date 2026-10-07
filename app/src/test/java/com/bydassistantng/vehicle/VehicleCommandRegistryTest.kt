package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the shipped `vehicle_commands.json`, which is the only thing standing between a voice
 * command and the car: every entry must load, none may be in a blocked domain, and the read-back /
 * offset wiring the newer entries depend on must be internally consistent. */
class VehicleCommandRegistryTest {
    private fun command(id: String) = checkNotNull(VehicleCommandRegistry.byId(id)) { "missing command $id" }

    @Test
    fun everyEntryLoads_andNoneIsBlocked() {
        // toSafeVehicleCommands drops (and logs) bad entries, so a count mismatch means one was dropped.
        assertEquals(VehicleCommandRegistry.entries.size, VehicleCommandRegistry.known.size)
        assertTrue(VehicleCommandRegistry.known.none { it.domain.isBlocked })
    }

    @Test
    fun functionNamesAreUniqueAndValidForGemini() {
        val names = VehicleCommandRegistry.functionDeclarations().map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) })
    }

    @Test
    fun genericCommandsHaveBothIds_andReadBackIsADifferentSignal() {
        for (c in VehicleCommandRegistry.known.filter { it.invocation == VehicleInvocation.GenericFeatureSet }) {
            assertNotNull("${c.id} deviceType", c.deviceType)
            assertNotNull("${c.id} featureId", c.featureId)
            if (c.stateFeatureId != null) assertTrue("${c.id}: the state id must not be the control id", c.stateFeatureId != c.featureId)
        }
    }

    @Test
    fun fridgeCoolingScreenValuesMapOntoTheObserved13to25Signal() {
        val cool = command("fridge.cool.temperature")
        val range = cool.parameter as VehicleParameter.Range
        // Recorded on the car: the cooling set-point signal runs 13..25 for the -6..6 °C the screen shows.
        assertEquals(13, range.min + cool.valueOffset)
        assertEquals(25, range.max + cool.valueOffset)
        // The car's default on switching to cooling was signal 22, which is 3 °C on the screen.
        assertEquals(22, 3 + cool.valueOffset)
    }

    @Test
    fun fridgeTemperatureIsRefusedUnlessTheFridgeIsCooling() {
        val mode = command("fridge.mode")
        val cool = command("fridge.cool.temperature")
        val requirement = checkNotNull(cool.requires)
        assertEquals(mode.stateFeatureId, requirement.featureId)
        assertEquals((mode.parameter as VehicleParameter.FixedEnum).options.getValue("cool"), requirement.equals)
    }

    @Test
    fun fridgeHeatingTemperatureIsTheScreenValueAndNeedsHeatingMode() {
        val heat = command("fridge.heat.temperature")
        val range = heat.parameter as VehicleParameter.Range
        // Confirmed by the user: the screen shows 35..50 °C, which is the signal as recorded (no offset).
        assertEquals(35, range.min)
        assertEquals(50, range.max)
        assertEquals(0, heat.valueOffset)
        val requirement = checkNotNull(heat.requires)
        assertEquals(command("fridge.mode").stateFeatureId, requirement.featureId)
        assertEquals((command("fridge.mode").parameter as VehicleParameter.FixedEnum).options.getValue("heat"), requirement.equals)
        // Cooling and heating share one control and one readback signal; only the mode precondition differs.
        assertEquals(command("fridge.cool.temperature").featureId, heat.featureId)
        assertEquals(command("fridge.cool.temperature").stateFeatureId, heat.stateFeatureId)
    }

    @Test
    fun windowPositionsAreGradualPercentControlsOnTheirOwnState() {
        val keys = listOf("driver", "passenger", "rearLeft", "rearRight")
        val controls = keys.map { command("window.$it.position") }
        for (c in controls) {
            assertEquals(VehicleParameter.Range(0, 100, "%"), c.parameter)
            assertTrue("${c.id} should verify by progress, not arrival", c.gradual && c.stateTolerance > 0)
            assertNotNull(c.stateFeatureId)
        }
        assertEquals(4, controls.map { it.featureId }.toSet().size)
        assertEquals(4, controls.map { it.stateFeatureId }.toSet().size)
    }

    @Test
    fun fridgeModeValuesMatchTheRecording() {
        val options = (command("fridge.mode").parameter as VehicleParameter.FixedEnum).options
        assertEquals(mapOf("off" to 3, "cool" to 1, "heat" to 2), options)
    }

    @Test
    fun fridgeDoorIsNeverExposed() {
        assertTrue(VehicleCommandRegistry.known.none { it.id.contains("door", ignoreCase = true) && it.id.startsWith("fridge") })
    }

    @Test
    fun everySeatHasHeatingAndVentilationWithTheSameThreeLevels() {
        for (seat in listOf("driver", "passenger", "rearLeft", "rearRight")) {
            for (kind in listOf("heating", "ventilation")) {
                val c = command("seat.$seat.$kind")
                assertEquals(mapOf("off" to 1, "low" to 2, "high" to 3), (c.parameter as VehicleParameter.FixedEnum).options)
                assertNotNull("seat.$seat.$kind needs a read-back", c.stateFeatureId)
            }
        }
    }

    @Test
    fun frontSeatsHaveMassageWithOffToHighAndReadBack() {
        for (seat in listOf("driver", "passenger")) {
            val c = command("seat.$seat.massage")
            assertEquals(mapOf("off" to 0, "low" to 1, "medium" to 2, "high" to 3), (c.parameter as VehicleParameter.FixedEnum).options)
            assertNotNull(c.stateFeatureId)
        }
        // Off is a MODE write (the native screen set mode 5->1 as the level went to 0); intensity 0 is ignored.
        assertEquals(listOf(ValueOverride(0, 1276190752, 1)), command("seat.driver.massage").overrides)
        assertEquals(listOf(ValueOverride(0, 1276190756, 1)), command("seat.passenger.massage").overrides)
        // Rear massage isn't fitted on this car (config flag 0, state unreadable) — nothing may claim it.
        assertTrue(VehicleCommandRegistry.known.none { it.id.startsWith("seat.rear") && it.id.endsWith("massage") })
    }

    @Test
    fun cabinLightIsOffOrOnWithReadBack() {
        val c = command("cabin.light")
        // Recorded from the car's own switch: state 1 = off, 2 = on (it dips to 0 for an instant going off).
        assertEquals(mapOf("off" to 1, "on" to 2), (c.parameter as VehicleParameter.FixedEnum).options)
        assertEquals(1121976365, c.stateFeatureId)
    }

    @Test
    fun acTogglesUseTheServicesOwnPropertyIds() {
        // Recorded from the car's A/C screen: the service logs these as PropertyValue{mId=…}.
        val expected = mapOf(
            "ac.airSource" to 109, "ac.ventilation" to 105, "ac.compressor" to 104,
            "ac.auto" to 114, "ac.frontDefrost" to 107, "ac.rearDefrost" to 108,
        )
        for ((id, property) in expected) {
            val invocation = command(id).invocation as VehicleInvocation.AcBinderProperty
            assertEquals("$id property", property, invocation.propertyId)
            assertEquals("$id must target the driver zone, the only one the service answers for", 256, invocation.area)
        }
        // Outside air is 0 (front defrost and ventilation both drove it to 0), recirculation is 1.
        assertEquals(mapOf("outside" to 0, "inside" to 1), (command("ac.airSource").parameter as VehicleParameter.FixedEnum).options)
    }

    @Test
    fun everyCommandHasABannerLabelInBothLanguages() {
        for (c in VehicleCommandRegistry.known) {
            assertTrue("${c.id} needs an English label", !c.label.isNullOrBlank())
            val arabic = c.labelAr
            assertTrue("${c.id} needs an Arabic label", !arabic.isNullOrBlank() && arabic.any { it in '\u0600'..'\u06FF' })
            assertEquals(c.label, c.labelFor(arabic = false))
            assertEquals(arabic, c.labelFor(arabic = true))
        }
    }

    @Test
    fun resolveValueKeepsNegativeRangesAndClamps() {
        val cool = command("fridge.cool.temperature")
        assertEquals(-3, VehicleCommandRegistry.resolveValue(cool, "-3"))
        assertEquals(6, VehicleCommandRegistry.resolveValue(cool, "30"))
        assertEquals(-6, VehicleCommandRegistry.resolveValue(cool, "-40"))
        assertNull(VehicleCommandRegistry.resolveValue(cool, "warm"))
    }

    @Test
    fun resolveValueMapsEnumNamesAndRejectsUnknownOnes() {
        val seat = command("seat.driver.heating")
        assertEquals(3, VehicleCommandRegistry.resolveValue(seat, "high"))
        assertNull(VehicleCommandRegistry.resolveValue(seat, "scorching"))
    }
}

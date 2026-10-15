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
    fun mirrorSwitchesAreConfirmedByTheirOwnStates() {
        // Recorded by operating the two switches on the Driving screen: each wrote its own control and state.
        val fold = command("mirrors.autoFold")
        assertEquals(1312817178, fold.featureId)
        assertEquals(1081081884, fold.stateFeatureId)
        assertEquals(mapOf("off" to 0, "on" to 1), (fold.parameter as VehicleParameter.FixedEnum).options)

        val tilt = command("mirrors.tiltInReverse")
        assertEquals(1312817180, tilt.featureId)
        assertEquals(1228931096, tilt.stateFeatureId)
        // Off is written as 2 (writing 0 is not what the car's switch does) and the state then reads 0.
        assertEquals(listOf(ValueOverride(0, 1312817180, 2)), tilt.overrides)
    }

    @Test
    fun ambientColorAndBrightnessAreWholeCabinMethodsWithReadBack() {
        val color = command("ambient.color")
        val colorCall = color.invocation as VehicleInvocation.NamedMethod
        assertEquals("setIALColor", colorCall.methodName)
        // Area 3 is the whole cabin (front and back rows), source 1 is "voice".
        assertEquals(listOf(3, null, 1), colorCall.argsTemplate)
        assertEquals(1121976336, color.stateFeatureId)
        assertEquals(1023, color.deviceType)
        val colors = (color.parameter as VehicleParameter.FixedEnum).options
        // The car's colour strip is 1..127; 0 is invalid and is refused by the car.
        assertTrue(colors.values.all { it in 1..127 })
        assertEquals(104, colors.getValue("red"))
        assertEquals(16, colors.getValue("blue"))

        val brightness = command("ambient.brightness")
        val range = brightness.parameter as VehicleParameter.Range
        // The method takes 0..5; the state reads 1..6, which is the level the user names.
        assertEquals(VehicleParameter.Range(1, 6), range)
        assertEquals(-1, brightness.valueOffset)
        assertEquals(0, brightness.stateOffset)
        assertEquals(listOf(3, null, 1), (brightness.invocation as VehicleInvocation.NamedMethod).argsTemplate)
    }

    @Test
    fun theDynamicColourSwitchIsOnTheLightDevice() {
        val dynamic = command("ambient.dynamic")
        assertEquals(VehicleDomain.LIGHT, dynamic.domain)
        assertEquals(1004, dynamic.deviceType)
        assertEquals(mapOf("on" to 1, "off" to 2), (dynamic.parameter as VehicleParameter.FixedEnum).options)
        assertEquals(679477578, dynamic.stateFeatureId)
    }

    @Test
    fun audioControlsMatchWhatTheNativeScreensWrote() {
        assertEquals(mapOf("unmute" to 1, "mute" to 2), (command("audio.mediaMute").parameter as VehicleParameter.FixedEnum).options)
        assertEquals(VehicleParameter.Range(0, 10), command("audio.navigationVolume").parameter)
        // Sound staging as the screen wrote it: whole car 6, front 7, back seats 4.
        assertEquals(
            mapOf("whole_car" to 6, "front" to 7, "back_seats" to 4),
            (command("audio.soundFocus").parameter as VehicleParameter.FixedEnum).options,
        )
        assertEquals(mapOf("soft" to 1, "standard" to 2, "strong" to 3), (command("audio.spaceSoundMode").parameter as VehicleParameter.FixedEnum).options)
        assertEquals(mapOf("off" to 1, "on" to 2), (command("audio.spaceSound").parameter as VehicleParameter.FixedEnum).options)
        for (id in listOf("audio.mediaMute", "audio.navigationVolume", "audio.soundFocus", "audio.spaceSound", "audio.spaceSoundMode")) {
            assertNotNull("$id needs a read-back", command(id).stateFeatureId)
        }
    }

    @Test
    fun onlyTheMediaVolumeIsAPlainAndroidCall() {
        assertEquals(listOf("audio.volume"), VehicleCommandRegistry.known.filter { !it.needsCarLink }.map { it.id })
        assertEquals(listOf("audio_volume"), VehicleCommandRegistry.functionDeclarations(carLink = false).map { it.name })
        assertEquals(VehicleCommandRegistry.known.size, VehicleCommandRegistry.functionDeclarations(carLink = true).size)
    }

    @Test
    fun volumeIsTheMediaStreamNotTheLegacyHalFeature() {
        // AUDIO_VOLUME_SET returned 0 and changed nothing; the car's volume is AudioManager stream 3, 0..39 here.
        val volume = command("audio.volume")
        assertEquals(VehicleInvocation.AudioStreamVolume(3), volume.invocation)
        assertEquals(VehicleParameter.Range(0, 39), volume.parameter)
    }

    @Test
    fun headrestSoundAndSoundStyleMatchWhatTheScreenWrote() {
        // Recorded from the Devialet dialogs: the screen wrote 0x9C100035 (2 surround, 3 interactive, 4 exclusive)
        // and 0x9B100018 (1..6); 1 (off) was written once by hand and read back.
        val headrest = command("audio.headrestSound")
        assertEquals(0x9C100035.toInt(), headrest.featureId)
        assertEquals(mapOf("off" to 1, "surround" to 2, "interactive" to 3, "exclusive" to 4), (headrest.parameter as VehicleParameter.FixedEnum).options)
        assertEquals(1253048341, headrest.stateFeatureId)
        val style = command("audio.soundStyle")
        assertEquals(0x9B100018.toInt(), style.featureId)
        assertEquals(
            mapOf("fidelity" to 1, "soft" to 2, "dynamic" to 3, "heavy" to 4, "speech" to 5, "custom" to 6),
            (style.parameter as VehicleParameter.FixedEnum).options,
        )
        assertEquals(0x4C600008, style.stateFeatureId)
    }

    @Test
    fun dashcamCommandsAreBinderCommandsToTheRecorderService() {
        val expected = mapOf(
            "dashcam.record" to 0, "dashcam.photo" to 1, "dashcam.lock" to 2,
            "dashcam.collisionSensitivity" to 5, "dashcam.audio" to 6,
        )
        for ((id, code) in expected) {
            val call = command(id).invocation as VehicleInvocation.BinderCommand
            assertEquals(id, "IBYDCDRService", call.serviceName)
            assertEquals(id, "android.cdrservice.IBYDCDRService", call.interfaceDescriptor)
            // IBYDCDRService.Stub: setCommand is transaction 1 (registerUser is 2, unregisterUser 3).
            assertEquals(id, 1, call.transactionCode)
            assertEquals(id, code, call.commandCode)
            assertTrue("$id has no success code", call.successCodes.isNotEmpty())
        }
        // A lock answers 2 or 3 (both mean it is being saved); the others answer 1.
        assertEquals(setOf(2, 3), (command("dashcam.lock").invocation as VehicleInvocation.BinderCommand).successCodes)
        assertEquals(setOf(1), (command("dashcam.record").invocation as VehicleInvocation.BinderCommand).successCodes)
        // The service's own refusals are worded for the user.
        val refusals = (command("dashcam.record").invocation as VehicleInvocation.BinderCommand).failureMessages
        assertTrue(refusals.getValue(24).contains("locked clip"))
        assertEquals(mapOf("low" to 6, "middle" to 7, "high" to 8), (command("dashcam.collisionSensitivity").parameter as VehicleParameter.FixedEnum).options)
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

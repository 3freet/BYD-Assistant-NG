package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The registry is offered to the model as one function per family; these guard that every command is still reachable
 * through exactly one (function, setting) pair and that nothing about the folding changes what a command does. */
class VehicleFamiliesTest {
    private val all = VehicleCommandRegistry.known

    private fun found(function: String, setting: String?, carLink: Boolean = true): VehicleCommand =
        (VehicleCommandRegistry.resolve(function, setting, carLink) as CommandLookup.Found).command

    @Test
    fun theRegistryIsOfferedAsFourteenFamilies() {
        assertEquals(
            listOf(
                "ac", "window", "sunroof", "sunshade", "audio", "trunk", "screen", "mirrors", "ambient", "fridge",
                "seat", "steeringWheel", "dashcam", "cabin",
            ).sorted(),
            VehicleCommandRegistry.functionNames().sorted(),
        )
    }

    @Test
    fun everyCommandIsReachableThroughItsFamilyAndSetting() {
        for (command in all) {
            assertEquals(command, found(command.family, command.setting))
        }
    }

    @Test
    fun aFamilysSettingsAreUnique() {
        for ((family, commands) in all.groupBy { it.family }) {
            assertEquals("duplicate setting in $family", commands.size, commands.map { it.setting.lowercase() }.toSet().size)
        }
    }

    @Test
    fun aFamilyNeverClashesWithAnotherTool() {
        val others = setOf(
            "navigate_to", "open_app", "media_control", "play_media", "get_volume", "read_car", "passenger_screen",
            "remember", "forget_memory", "end_conversation", "speak_outside", "play_sound_outside", "set_drive_mode",
        )
        assertTrue(VehicleCommandRegistry.functionNames().none { it in others })
    }

    @Test
    fun theSettingIsAnEnumOfTheFamilysOwnNames() {
        val seat = VehicleCommandRegistry.functionDeclarations().first { it.name == "seat" }
        val setting = checkNotNull(seat.parameters?.properties?.get("setting"))
        assertEquals(all.filter { it.family == "seat" }.map { it.setting }, setting.enum)
        assertTrue("driver.heating" in setting.enum!!)
        assertEquals(listOf("setting", "value"), seat.parameters?.required)
    }

    @Test
    fun aFamilyWithOneSettingNeedsNone() {
        for (name in listOf("trunk", "sunshade", "screen", "steeringWheel", "cabin")) {
            val declaration = VehicleCommandRegistry.functionDeclarations().first { it.name == name }
            assertEquals("$name has only a value", setOf("value"), declaration.parameters?.properties?.keys)
            assertEquals(listOf("value"), declaration.parameters?.required)
            // …and a call without a setting still means its only command.
            assertEquals(name, found(name, null).family)
        }
    }

    @Test
    fun theLoneSettingOfAnIdWithoutADotIsTheState() {
        assertEquals("state", command("trunk").setting)
        assertEquals("state", command("sunroof").setting)
        assertEquals("openPercent", command("sunroof.openPercent").setting)
        assertEquals("driver.heating", command("seat.driver.heating").setting)
        assertEquals("cool.temperature", command("fridge.cool.temperature").setting)
    }

    private fun command(id: String) = checkNotNull(VehicleCommandRegistry.byId(id))

    @Test
    fun aFamilyWithSeveralSettingsNeedsOne() {
        val answer = VehicleCommandRegistry.resolve("seat", null)
        assertTrue(answer is CommandLookup.UnknownSetting)
        assertTrue("driver.heating" in (answer as CommandLookup.UnknownSetting).settings)
    }

    @Test
    fun anUnknownSettingOrFunctionIsReportedNotGuessed() {
        assertEquals(CommandLookup.UnknownFunction, VehicleCommandRegistry.resolve("seat_driver_heating", null))
        assertEquals(CommandLookup.UnknownFunction, VehicleCommandRegistry.resolve("nope", "x"))
        val unknown = VehicleCommandRegistry.resolve("seat", "driver.nothing") as CommandLookup.UnknownSetting
        assertFalse("driver.nothing" in unknown.settings)
        assertTrue(unknown.settings.isNotEmpty())
    }

    @Test
    fun theSettingIsMatchedWithoutRegardToCase() {
        assertEquals("seat.rearLeft.heating", found("seat", "rearleft.HEATING").id)
        assertEquals("window.rearRight", found("window", " rearRight ").id)
    }

    @Test
    fun withoutTheCarLinkOnlyThePlainSettingIsOfferedButOthersStillResolve() {
        val audio = VehicleCommandRegistry.functionDeclarations(carLink = false).single()
        assertEquals("audio", audio.name)
        assertEquals(setOf("value"), audio.parameters?.properties?.keys)
        assertEquals("audio.volume", found("audio", null, carLink = false).id)
        // A setting that needs the link is not offered, but a call that names it is not misread as the volume.
        assertEquals("audio.mediaMute", found("audio", "mediaMute", carLink = false).id)
        assertTrue(VehicleCommandRegistry.resolve("seat", null, carLink = false) is CommandLookup.UnknownSetting)
    }

    @Test
    fun aFamilyWithOneSettingResolvesWithoutTheLinkToo() {
        // Not offered then, but a call that arrives anyway reaches the dispatcher's gate (which stubs it), not an error.
        for (name in listOf("trunk", "sunshade", "screen", "steeringWheel", "cabin")) {
            assertEquals(name, found(name, null, carLink = false).family)
        }
    }

    @Test
    fun theDescriptionNamesEverySettingAndItsValues() {
        for (declaration in VehicleCommandRegistry.functionDeclarations()) {
            val commands = all.filter { it.family == declaration.name }
            val text = declaration.description
            for (command in commands) {
                assertTrue("${declaration.name} lacks ${command.setting}", commands.size == 1 || text.contains("${command.setting}: "))
                assertTrue("${command.id} lacks its values", text.contains(command.valuesText()))
            }
        }
    }

    @Test
    fun theWholeRegistryStaysSmall() {
        // The point of folding: the declarations cost the model's context on every request. About 3,400 tokens flat;
        // keep the folded form under 2,700 (characters / 4 as the estimate).
        val chars = VehicleCommandRegistry.functionDeclarations().sumOf { it.name.length + it.description.length + 120 }
        assertTrue("registry declarations are $chars characters", chars / 4 < 2_700)
    }

    @Test
    fun valuesAreResolvedForEnumsAndRanges() {
        val heating = command("seat.driver.heating")
        assertEquals(3, VehicleCommandRegistry.resolveValue(heating, "high"))
        assertEquals(3, VehicleCommandRegistry.resolveValue(heating, " High "))
        assertNull(VehicleCommandRegistry.resolveValue(heating, "scalding"))
        assertNull(VehicleCommandRegistry.resolveValue(heating, null))
        val temperature = command("ac.temperature")
        assertEquals(22, VehicleCommandRegistry.resolveValue(temperature, "22"))
        assertEquals(22, VehicleCommandRegistry.resolveValue(temperature, "22.0"))
        assertEquals(33, VehicleCommandRegistry.resolveValue(temperature, "40"))
        assertNull(VehicleCommandRegistry.resolveValue(temperature, "22.5"))
        assertNull(VehicleCommandRegistry.resolveValue(temperature, "warm"))
    }

    @Test
    fun valuesTextIsWhatTheModelIsToldToSend() {
        assertEquals("off|low|high", command("seat.driver.heating").valuesText())
        assertEquals("17..33 °C", command("ac.temperature").valuesText())
    }

    @Test
    fun everyCommandStillHasItsBannerLabelAndNothingIsBlocked() {
        assertTrue(all.none { it.domain.isBlocked })
        assertNotNull(command("audio.volume").labelFor(arabic = false))
    }
}

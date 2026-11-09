package com.bydassistantng.gemini

import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.vehicle.DriveModeControl
import com.bydassistantng.vehicle.DriveModeRoute
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the assistant says when it is asked for a feature that exists but is switched off in Settings, on every car and every route. */
class SwitchedOffFeaturesTest {
    private val on = FeatureState(present = true, on = true)
    private val off = FeatureState(present = true, on = false)
    private val missing = FeatureState(present = false, on = false)

    private fun states(
        vehicleControlOn: Boolean = true,
        exteriorSpeaker: FeatureState = on,
        driveModes: FeatureState = on,
        cluster: FeatureState = on,
        webSearchOn: Boolean = true,
        longTermMemoryOn: Boolean = true,
    ) = SwitchedOffFeatures.states(vehicleControlOn, exteriorSpeaker, driveModes, cluster, webSearchOn, longTermMemoryOn)

    private fun pathOf(feature: SwitchableFeature) = "Settings > ${feature.name}"

    @Test
    fun aFeatureTheCarHasAndTheUserLeftOffIsNamedWithWhereToSwitchItOn() {
        val list = SwitchedOffFeatures.switchedOff(states(driveModes = off), ::pathOf)

        assertEquals(listOf(SwitchedOff(SwitchableFeature.DRIVE_MODES, "Settings > DRIVE_MODES")), list)
        val section = SwitchedOffFeatures.promptSection(list, emptyList())!!
        assertTrue(section, "Settings > DRIVE_MODES" in section)
        assertTrue(section, SwitchableFeature.DRIVE_MODES.covers in section)
    }

    @Test
    fun theAssistantIsToldNotToBlameTheSystemAndToSayWhereToSwitchItOn() {
        val section = SwitchedOffFeatures.promptSection(listOf(SwitchedOff(SwitchableFeature.DRIVE_MODES, "x")), emptyList())!!

        assertTrue(section, "do not say that you cannot or that the system or the car does not allow it" in section)
        assertTrue(section, "where to switch it on" in section)
        assertTrue(section, "next conversation" in section)
    }

    @Test
    fun aFeatureThatIsOnIsNotMentionedAndNothingIsAddedWhenAllIsOn() {
        assertTrue(SwitchedOffFeatures.switchedOff(states(), ::pathOf).isEmpty())
        assertTrue(SwitchedOffFeatures.absent(states()).isEmpty())
        assertNull(SwitchedOffFeatures.promptSection(emptyList(), emptyList()))
    }

    @Test
    fun aFeatureTheCarDoesNotHaveIsSaidNotToBeOfferedAndNoPathIsGiven() {
        val s = states(exteriorSpeaker = missing, driveModes = missing, cluster = missing)

        assertTrue(SwitchedOffFeatures.switchedOff(s, ::pathOf).isEmpty())
        assertEquals(
            listOf(SwitchableFeature.EXTERIOR_SPEAKER, SwitchableFeature.DRIVE_MODES, SwitchableFeature.CLUSTER_SCREEN),
            SwitchedOffFeatures.absent(s),
        )
        val section = SwitchedOffFeatures.promptSection(emptyList(), SwitchedOffFeatures.absent(s))!!
        assertTrue(section, "does not offer" in section)
        assertFalse(section, "Settings >" in section)
        assertFalse(section, "switch it on" in section)
    }

    @Test
    fun theSameRuleHoldsForEveryRouteTheDriveModeTakes() {
        fun table(vararg entries: Pair<String, Int>): (String, String) -> Int? {
            val map = entries.toMap()
            return { group, name -> map["$group.$name"] }
        }
        val leopard = table("Setting.${DriveModeControl.PRESELECT_NAME}" to DriveModeControl.PRESELECT_ID)
        val classic = table("Energy.ENERGY_OPERATION_MODE_SET" to 1, "Energy.ENERGY_OPERATION_MODE" to 2)
        val none = table()
        val results = mapOf(
            DriveModeRoute.SETTING to DriveModeControl.route(leopard, preferred = ""),
            DriveModeRoute.OPERATION_MODE to DriveModeControl.route(classic, preferred = ""),
            DriveModeRoute.NONE to DriveModeControl.route(none, preferred = ""),
        )
        for ((expected, route) in results) assertEquals(expected, route)

        for (route in listOf(DriveModeRoute.SETTING, DriveModeRoute.OPERATION_MODE)) {
            // A car with a route to the drive mode and the switch off: the user is told where to switch it on.
            val s = states(driveModes = FeatureState(present = route != DriveModeRoute.NONE, on = false))
            assertEquals("$route", listOf(SwitchableFeature.DRIVE_MODES), SwitchedOffFeatures.switchedOff(s, ::pathOf).map { it.feature })
            assertTrue("$route", SwitchedOffFeatures.absent(s).isEmpty())
        }
        // A car with no route has nothing to switch on: it is told the car does not offer it.
        val s = states(driveModes = FeatureState(present = results.getValue(DriveModeRoute.NONE) != DriveModeRoute.NONE, on = false))
        assertTrue(SwitchedOffFeatures.switchedOff(s, ::pathOf).isEmpty())
        assertEquals(listOf(SwitchableFeature.DRIVE_MODES), SwitchedOffFeatures.absent(s))
    }

    @Test
    fun vehicleControlThatIsOffIsListedAndTheFeaturesThatNeedItSaySo() {
        val s = states(vehicleControlOn = false, driveModes = off, exteriorSpeaker = off, cluster = off)

        val list = SwitchedOffFeatures.switchedOff(s, ::pathOf)
        assertEquals(
            listOf(SwitchableFeature.VEHICLE_CONTROL, SwitchableFeature.EXTERIOR_SPEAKER, SwitchableFeature.DRIVE_MODES, SwitchableFeature.CLUSTER_SCREEN),
            list.map { it.feature },
        )
        val section = SwitchedOffFeatures.promptSection(list, emptyList())!!
        assertEquals(3, Regex("it also needs vehicle control on").findAll(section).count())
        assertTrue(section, "Settings > VEHICLE_CONTROL" in section)
    }

    @Test
    fun theFeaturesWithoutACarPartAreCoveredToo() {
        val list = SwitchedOffFeatures.switchedOff(states(webSearchOn = false, longTermMemoryOn = false), ::pathOf)

        assertEquals(listOf(SwitchableFeature.WEB_SEARCH, SwitchableFeature.LONG_TERM_MEMORY), list.map { it.feature })
        assertFalse(SwitchedOffFeatures.promptSection(list, emptyList())!!.contains("it also needs vehicle control on"))
    }

    @Test
    fun aToolAnswerSaysWhereToSwitchItOnAndNeverBlamesTheSystem() {
        val answer = SwitchedOffFeatures.toolAnswer(SwitchedOff(SwitchableFeature.DRIVE_MODES, "Settings > Car > Experimental features > Drive mode switching"))

        assertEquals("error", (answer["status"] as JsonPrimitive).content)
        assertTrue((answer["error"] as JsonPrimitive).content.contains("Drive mode switching"))
        val instruction = (answer["instruction"] as JsonPrimitive).content
        assertTrue(instruction, "Settings > Car > Experimental features > Drive mode switching" in instruction)
        assertTrue(instruction, "Do not say that the system or the car does not allow it" in instruction)
    }

    @Test
    fun aToolAnswerForAFeatureTheCarLacksSaysSoAndOffersNoSwitch() {
        val answer = SwitchedOffFeatures.unavailableAnswer(SwitchableFeature.EXTERIOR_SPEAKER)

        assertTrue((answer["error"] as JsonPrimitive).content.contains("does not offer"))
        assertTrue((answer["instruction"] as JsonPrimitive).content.contains("No setting changes that"))
    }

    @Test
    fun thePromptCarriesTheSectionOnlyWhenThereIsSomethingToSay() {
        val plain = voiceAssistantSystemPrompt(AssistantLanguage.AUTO, conversation = true)
        val told = voiceAssistantSystemPrompt(
            AssistantLanguage.AUTO,
            conversation = true,
            switchedOff = listOf(SwitchedOff(SwitchableFeature.DRIVE_MODES, "Settings > Car > Drive mode switching")),
            notOnThisCar = listOf(SwitchableFeature.CLUSTER_SCREEN),
        )

        assertFalse(plain, "switched off in the app's Settings" in plain)
        assertTrue(told, "switched off in the app's Settings" in told)
        assertTrue(told, "Settings > Car > Drive mode switching" in told)
        assertTrue(told, "does not offer the following" in told)
        // What was already there is still there.
        assertTrue(told.startsWith(plain.substringBefore(" This is an ongoing conversation")))
    }

    @Test
    fun theGeneralRuleForAnythingElseNoLongerInvitesAVagueRefusal() {
        val prompt = voiceAssistantSystemPrompt(AssistantLanguage.AUTO)

        assertTrue(prompt, "say plainly that you cannot do that" in prompt)
        assertTrue(prompt, "do not say that the system or the car does not allow it" in prompt)
    }

    @Test
    fun aPathIsWrittenWithTheScreensInOrder() {
        assertEquals("Settings > Car > Vehicle control", SettingsPaths.compose("Settings", "Car", "Vehicle control"))
    }
}

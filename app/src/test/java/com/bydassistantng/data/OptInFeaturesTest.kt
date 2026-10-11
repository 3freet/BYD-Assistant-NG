package com.bydassistantng.data

import com.bydassistantng.gemini.assistantTools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptInFeaturesTest {
    @Test
    fun theOptInCarFeaturesAreOffUntilTheUserSwitchesThemOn() {
        assertFalse(OptInFeatures.EXTERIOR_SPEAKER_DEFAULT)
        assertFalse(OptInFeatures.DRIVE_MODES_DEFAULT)
    }

    @Test
    fun withTheDefaultsNeitherFeatureIsOfferedToTheAssistant() {
        // The conversation passes "built in AND switched on"; with the defaults that is false for both.
        val names = assistantTools(
            vehicleControlEnabled = true,
            conversation = true,
            exteriorSpeaker = true && OptInFeatures.EXTERIOR_SPEAKER_DEFAULT,
            driveModes = true && OptInFeatures.DRIVE_MODES_DEFAULT,
        ).flatMap { it.functionDeclarations.orEmpty() }.map { it.name }
        assertFalse("speak_outside" in names)
        assertFalse("play_sound_outside" in names)
        assertFalse("set_drive_mode" in names)
    }

    @Test
    fun vehicleControlIsOnByDefaultAsTheCoreOfTheAssistant() {
        assertTrue(CoreFeatures.VEHICLE_CONTROL_DEFAULT)
    }
}

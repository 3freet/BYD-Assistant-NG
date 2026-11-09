package com.bydassistantng.vehicle

import com.bydassistantng.audio.ExteriorSpeakerSwitch
import com.bydassistantng.vehicle.FeatureNameCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearNamesTest {
    /** A few names as a car's table has them (the numbers are the Leopard 5's), with the top-level repeat of a grouped name. */
    private val table = mapOf(
        "Setting.SETTING_TARGET_DRIVING_MODE" to 255852712,
        "Setting.SETTING_ACTUAL_DRIVING_MODE" to 754975128,
        "Setting.SETTING_DRIVING_MODE_KEY" to 1250951191,
        "Setting.SETTING_ACTUAL_DRIVING_MODE_hal_only" to 1272971272,
        "Setting.SETTING_ECO_MODE_CONFIGURATION_SIGN" to 1219518496,
        "Bodywork.BODYWORK_MAIN_DRIVER_MASSAGE_SWITCH_OPERATION_MODE" to 1261437420,
        "Audio.AUDIO_AVAS_FAULT_STATUS" to 891293762,
        "Audio.AUDIO_EXTERIOR_PROMPT_TONE_SOURCE_SET" to 454033475,
        "Audio.AUDIO_LOCK_CAR_SOUND_EFFECT_PLAYBACK_STATUS_SET" to -1442839775,
        "Adas.ADAS_UE_BROADCAST_ENDING_FLAG_SET" to -1442839933,
        "Test.TEST_AUDIO_AVAS_SET" to -1442840316,
        "Energy.ENERGY_OPERATION_MODE" to 555745294,
        "Energy.ENERGY_OPERATION_MODE_SET" to 734003229,
        "Energy.ENERGY_ROAD_SURFACE_MODE" to 603979827,
        "Energy.ENERGY_MODE_SET" to 734003254,
        "ENERGY_MODE_SET" to 734003254,
        "Audio.AUDIO_EXTERIOR_SPEAKER_SWITCH_SET" to 470810638,
        "Audio.AUDIO_EXTERIOR_SPEAKER_SWITCH_STATUS" to 891293760,
        "Audio.AUDIO_EXTERIOR_SPEAKER_CONFIG" to 891293750,
        "Audio.AUDIO_AVAS_SOURCE_TYPE" to -1728052894,
        "Bodywork.BODYWORK_LF_WINDOW_CTRL_SET" to 1125122104,
        "Ac.AC_CTRL_MODE" to 1077936146,
    )

    private fun result(owner: String, name: String, verdict: Verdict) =
        FeatureNameCheck.Result(FeatureNameCheck.Reference(owner, "", name, 1), if (verdict == Verdict.ABSENT) null else 1, verdict)

    private val driveName = "${DriveModeControl.FEATURE_GROUP}.${DriveModeControl.PRESELECT_NAME}"
    private val speakerName = "${ExteriorSpeakerSwitch.FEATURE_GROUP}.${ExteriorSpeakerSwitch.FEATURE_NAME}"

    @Test
    fun aMissingNameIsMatchedToTheNamesOfTheTableThatShareItsWords() {
        val line = NearNames.similar(listOf("Setting.SETTING_PRESELECTED_DRIVING_MODE_SET"), table)!!

        assertTrue(line, line.startsWith("Setting.SETTING_PRESELECTED_DRIVING_MODE_SET:"))
        assertTrue(line, "Setting.SETTING_TARGET_DRIVING_MODE=255852712" in line)
        assertTrue(line, "Setting.SETTING_ACTUAL_DRIVING_MODE=754975128" in line)
        // One shared word (MODE) is not enough for a name that has three words of its own, so these are left out.
        assertFalse(line, "Energy.ENERGY_OPERATION_MODE" in line)
        assertFalse(line, "Ac.AC_CTRL_MODE" in line)
    }

    @Test
    fun theWordsThatOnlySayWhatKindOfEntryItIsAndTheGroupsWordMakeNoMatch() {
        assertNull(NearNames.similar(listOf("Setting.SETTING_SET"), table))
        assertNull(NearNames.similar(listOf("Bodywork.BODYWORK_NOTHING_LIKE_IT_SET"), table))
    }

    @Test
    fun aNameWithOneWordOfItsOwnNeedsOnlyThatWord() {
        val line = NearNames.similar(listOf("Audio.AUDIO_AVAS_SET"), table)!!

        assertTrue(line, line.startsWith("Audio.AUDIO_AVAS_SET:"))
        assertTrue(line, "Audio.AUDIO_AVAS_SOURCE_TYPE=-1728052894" in line)
        // The same group and the closer name come first.
        assertTrue(line, line.indexOf("Audio.AUDIO_AVAS_FAULT_STATUS") < line.indexOf("Test.TEST_AUDIO_AVAS_SET"))
    }

    @Test
    fun theNamesAreWrittenWithoutThePrefixOfTheClassItselfWhenAGroupHasThemToo() {
        val line = NearNames.scan(NearNames.topics.first { it.key == "drive" }.pattern, table)!!

        assertTrue(line, "Energy.ENERGY_MODE_SET=734003254" in line)
        assertFalse(line, line.split(',').any { it == "ENERGY_MODE_SET=734003254" })
    }

    @Test
    fun theDriveTopicFindsTheClassicEnergyModeThatSharesNoWordWithOurName() {
        val line = NearNames.scan(NearNames.topics.first { it.key == "drive" }.pattern, table)!!

        assertTrue(line, "Energy.ENERGY_OPERATION_MODE_SET=734003229" in line)
        assertTrue(line, "Energy.ENERGY_OPERATION_MODE=555745294" in line)
        // The terrain modes of the classic energy device (snow, mud, sand) have a state of their own.
        assertTrue(line, "Energy.ENERGY_ROAD_SURFACE_MODE=603979827" in line)
        assertTrue(line, "Setting.SETTING_TARGET_DRIVING_MODE=255852712" in line)
        assertFalse(line, "Audio." in line)
    }

    @Test
    fun theHalOnlyCopiesOfNamesAreNotListedSoTheyDoNotTakeThePlaceOfAnotherName() {
        val similar = NearNames.similar(listOf("Setting.SETTING_PRESELECTED_DRIVING_MODE_SET"), table)!!
        val scan = NearNames.scan(NearNames.topics.first { it.key == "drive" }.pattern, table)!!

        assertFalse(similar, "_hal_only" in similar)
        assertFalse(scan, "_hal_only" in scan)
    }

    @Test
    fun namesThatOnlyLookLikeATopicsAreLeftOut() {
        val drive = NearNames.scan(NearNames.topics.first { it.key == "drive" }.pattern, table)!!
        val speaker = NearNames.scan(NearNames.topics.first { it.key == "speaker" }.pattern, table)!!

        assertFalse("a seat massage has an operation mode: $drive", "MASSAGE" in drive)
        assertFalse("a fault flag has the speaker's name in it: $speaker", "FAULT" in speaker)
        assertFalse("a factory test entry: $speaker", "TEST_" in speaker)
        assertTrue("the exterior prompt tone is the speaker's: $speaker", "Audio.AUDIO_EXTERIOR_PROMPT_TONE_SOURCE_SET=454033475" in speaker)
    }

    @Test
    fun theSpeakerTopicFindsTheExteriorSpeakerAndAvasNames() {
        val line = NearNames.scan(NearNames.topics.first { it.key == "speaker" }.pattern, table)!!

        assertTrue(line, "Audio.AUDIO_EXTERIOR_SPEAKER_SWITCH_SET=470810638" in line)
        assertTrue(line, "Audio.AUDIO_EXTERIOR_SPEAKER_SWITCH_STATUS=891293760" in line)
        assertTrue(line, "Audio.AUDIO_EXTERIOR_SPEAKER_CONFIG=891293750" in line)
        assertTrue(line, "Audio.AUDIO_AVAS_SOURCE_TYPE=-1728052894" in line)
        // The two names an app that plays audio outside the car writes besides the switch itself.
        assertTrue(line, "Audio.AUDIO_LOCK_CAR_SOUND_EFFECT_PLAYBACK_STATUS_SET=-1442839775" in line)
        assertTrue(line, "Adas.ADAS_UE_BROADCAST_ENDING_FLAG_SET=-1442839933" in line)
    }

    @Test
    fun aTopicIsOnlyScannedWhenItsFeatureIsNotOfferedOnThisCar() {
        val results = listOf(
            result("driveMode.preselect", driveName, Verdict.SAME),
            result("exteriorSpeaker.switch", speakerName, Verdict.ABSENT),
        )

        val fields = NearNames.fields(results, table)!!

        assertFalse(fields, "scan.drive=" in fields)
        assertTrue(fields, "scan.speaker=" in fields)
    }

    @Test
    fun aFeatureThatTheCarNumbersOtherwiseIsScannedToo() {
        val fields = NearNames.fields(listOf(result("driveMode.preselect", driveName, Verdict.DIFFERENT)), table)!!

        assertTrue(fields, "scan.drive=" in fields)
    }

    @Test
    fun aCarThatHasEveryNameGetsNoFields() {
        val results = listOf(result("driveMode.preselect", driveName, Verdict.SAME), result("window.driver", "Bodywork.BODYWORK_LF_WINDOW_CTRL_SET", Verdict.SAME))

        assertNull(NearNames.fields(results, table))
    }

    @Test
    fun theFeaturesTheAppCanOfferAreLookedUpBeforeTheCommands() {
        val others = (1..30).map { result("window.$it", "Bodywork.BODYWORK_LF_WINDOW_CTRL_SET_$it", Verdict.ABSENT) }
        val results = others + result("driveMode.preselect", driveName, Verdict.ABSENT)

        val fields = NearNames.fields(results, table)!!

        // With 31 names missing and room for 24, the drive mode must be among those looked up: it comes first.
        assertTrue(fields, fields.startsWith("similar=$driveName:"))
        assertTrue(fields, ",+7 not looked up" in fields)
    }

    @Test
    fun aTopicListsAtMostFortyNamesAndSaysHowManyMore() {
        val big = (1..60).associate { "Setting.SETTING_DRIVING_MODE_$it" to it }

        val line = NearNames.scan(NearNames.topics.first { it.key == "drive" }.pattern, big)!!

        assertEquals(40, line.substringBefore(",+").split(',').size)
        assertTrue(line, line.endsWith(",+20 more"))
    }

    @Test
    fun noTableMeansNoFields() {
        assertNull(NearNames.similar(listOf("Setting.SETTING_X_Y"), emptyMap()))
        assertNull(NearNames.scan(NearNames.topics.first().pattern, emptyMap()))
    }

    @Test
    fun everyTopicNamesAFeatureTheAppKnowsAndMatchesTheNamesItWasFoundUnder() {
        for (topic in NearNames.topics) {
            val feature = OtherFeatures.all.firstOrNull { it.label == topic.feature }
            assertNotNull("no feature labelled ${topic.feature}", feature)
            // The topic must at least find the name the feature has on the car it was verified on.
            assertTrue(topic.key, topic.pattern.containsMatchIn(feature!!.name.substringAfter('.')))
        }
    }
}

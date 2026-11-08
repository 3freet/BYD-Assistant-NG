package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatePeekTest {
    private val table = mapOf(
        "Audio.AUDIO_EXTERIOR_SPEAKER_CONFIG" to 891293750,
        "Audio.AUDIO_EXTERIOR_SPEAKER_SWITCH_STATUS" to 891293760,
        "Energy.ENERGY_OPERATION_MODE" to 555745294,
        "Bodywork.BODYWORK_LF_WINDOW_CTRL_SET" to 1125122104,
    )

    @Test
    fun onlyTheStatesTheCarsTableHasAreReadAndTheNumbersAreWrittenAsTheCarGivesThem() {
        val asked = ArrayList<Pair<Int, Int>>()

        val line = StatePeek.describe(table) { deviceType, id -> asked += deviceType to id; if (id == 891293750) 1 else 0 }

        assertEquals(
            "Audio.AUDIO_EXTERIOR_SPEAKER_CONFIG:1,Audio.AUDIO_EXTERIOR_SPEAKER_SWITCH_STATUS:0,Energy.ENERGY_OPERATION_MODE:0",
            line,
        )
        // Each is read on its own device: the audio device for the speaker's states, the energy device for the mode.
        assertEquals(listOf(1002 to 891293750, 1002 to 891293760, 1006 to 555745294), asked)
    }

    @Test
    fun aStateTheCarDoesNotAnswerIsAQuestionMarkAndNotLeftOut() {
        val line = StatePeek.describe(table) { _, id -> if (id == 891293760) null else 5 }!!

        assertTrue(line, "Audio.AUDIO_EXTERIOR_SPEAKER_SWITCH_STATUS:?" in line)
        assertTrue(line, "Audio.AUDIO_EXTERIOR_SPEAKER_CONFIG:5" in line)
    }

    @Test
    fun aCarWithNoneOfTheStatesGetsNoField() {
        assertNull(StatePeek.describe(mapOf("Bodywork.BODYWORK_LF_WINDOW_CTRL_SET" to 1)) { _, _ -> 0 })
        assertNull(StatePeek.describe(emptyMap()) { _, _ -> 0 })
    }

    @Test
    fun nothingIsWrittenByLookingOnlyReadsAreMade() {
        // describe() is given a reader and nothing else, so it cannot write; the reads are the car's own states.
        assertEquals(9, StatePeek.probes.size)
        assertTrue(StatePeek.probes.all { '.' in it.name })
    }
}

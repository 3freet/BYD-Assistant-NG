package com.bydassistantng.gemini

import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantLanguage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSetupTest {
    // The same settings the Live client encodes with.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun names(tools: List<GeminiTool>) = tools.flatMap { it.functionDeclarations.orEmpty() }.map { it.name }

    @Test
    fun navigationAppsAndMediaAreAlwaysOffered() {
        val names = names(assistantTools(vehicleControlEnabled = false, conversation = true))
        assertTrue(names.containsAll(listOf("navigate_to", "open_app", "media_control", "end_conversation")))
        assertFalse(names.any { it.startsWith("fridge_") || it.startsWith("window_") })
    }

    @Test
    fun vehicleCommandsOnlyWhenArmed() {
        assertTrue(names(assistantTools(vehicleControlEnabled = true)).any { it.startsWith("fridge_") })
    }

    @Test
    fun speakingOutsideNeedsABuildThatCanDoItAndArmedVehicleControl() {
        val tool = "speak_outside"
        // Never in a build without an exterior speaker, armed or not.
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = true, conversation = true)))
        // Not without vehicle control armed, even where the build has it.
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = false, conversation = true, exteriorSpeaker = true)))
        // Not in a one-shot request, which has no way to play a spoken reply there.
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = true, conversation = false, exteriorSpeaker = true)))
        assertTrue(tool in names(assistantTools(vehicleControlEnabled = true, conversation = true, exteriorSpeaker = true)))
    }

    @Test
    fun soundEffectsAreOfferedOnlyWithTheSpeakerAndTheirOwnList() {
        val tool = "play_sound_outside"
        val effects = listOf(com.bydassistantng.audio.SoundEffect("doorbell", "a ding-dong"))
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = true, conversation = true, exteriorSpeaker = true)))
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = true, conversation = true, soundEffects = effects)))
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = false, conversation = true, exteriorSpeaker = true, soundEffects = effects)))
        assertTrue(tool in names(assistantTools(vehicleControlEnabled = true, conversation = true, exteriorSpeaker = true, soundEffects = effects)))
    }

    @Test
    fun driveModesNeedABuildThatCanDoThemAndArmedVehicleControl() {
        val tool = "set_drive_mode"
        // Never in a build without a drive mode switch, armed or not.
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = true, conversation = true)))
        // Not without vehicle control armed, even where the build has it.
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = false, conversation = true, driveModes = true)))
        // Not in a one-shot request: there is no conversation to hear what the car answered.
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = true, conversation = false, driveModes = true)))
        assertTrue(tool in names(assistantTools(vehicleControlEnabled = true, conversation = true, driveModes = true)))
    }

    @Test
    fun webSearchIsAnExtraToolOnlyWhenOn() {
        assertTrue(assistantTools(false, webSearch = false).none { it.googleSearch != null })
        val withSearch = assistantTools(false, webSearch = true)
        assertEquals(1, withSearch.count { it.googleSearch != null })
        // Search is its own tool object, not mixed into the function list.
        assertTrue(withSearch.filter { it.googleSearch != null }.all { it.functionDeclarations == null })
    }

    @Test
    fun theSearchToolIsEncodedAsAnEmptyObject_andFunctionToolsLeaveItOut() {
        val tools = assistantTools(false, webSearch = true)
        val setup = LiveClientSetupMessage(LiveSetup(model = "models/m", generationConfig = LiveGenerationConfig(listOf("AUDIO")), tools = tools))
        val text = json.encodeToString(LiveClientSetupMessage.serializer(), setup)
        assertTrue(text, text.contains("\"googleSearch\":{}"))
        // The function-declarations tool must not carry a null googleSearch (the API would reject the field).
        val functionTool = json.encodeToString(GeminiTool.serializer(), tools.first())
        assertFalse(functionTool, functionTool.contains("googleSearch"))
    }

    @Test
    fun theVoiceIsSentOnlyWhenChosen() {
        fun encode(config: LiveGenerationConfig) = json.encodeToString(LiveGenerationConfig.serializer(), config)
        assertFalse(encode(LiveGenerationConfig(listOf("AUDIO"))).contains("speechConfig"))
        val withVoice = encode(LiveGenerationConfig(listOf("AUDIO"), LiveSpeechConfig(LiveVoiceConfig(LivePrebuiltVoiceConfig("Kore")))))
        assertTrue(withVoice, withVoice.contains("\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":\"Kore\"}}}"))
    }

    @Test
    fun theDialectIsOnlyMentionedWhenItCanMatter() {
        val gulf = voiceAssistantSystemPrompt(AssistantLanguage.AUTO, dialect = ArabicDialect.GULF)
        assertTrue(gulf, gulf.contains("speak Gulf Arabic"))
        assertFalse(voiceAssistantSystemPrompt(AssistantLanguage.AUTO, dialect = ArabicDialect.MATCH).contains("dialect —"))
        // A fixed-English assistant never speaks Arabic, so telling it which would only be noise.
        assertFalse(voiceAssistantSystemPrompt(AssistantLanguage.ENGLISH, dialect = ArabicDialect.GULF).contains("Gulf Arabic"))
    }

    @Test
    fun searchGuidanceIsOnlyGivenWhenSearchIsOn() {
        assertTrue(voiceAssistantSystemPrompt(AssistantLanguage.AUTO, webSearch = true).contains("Google Search"))
        assertFalse(voiceAssistantSystemPrompt(AssistantLanguage.AUTO, webSearch = false).contains("Google Search"))
    }

    @Test
    fun thePromptTellsTheModelWhatTheUserSpeaksAndAsksAbout() {
        val prompt = voiceAssistantSystemPrompt(AssistantLanguage.AUTO)
        assertTrue(prompt.contains("in any dialect"))
        assertTrue(prompt.contains("massage"))
        assertTrue(prompt.contains("open_app") && prompt.contains("media_control"))
    }
}

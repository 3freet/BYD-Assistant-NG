package com.bydassistantng.gemini

import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantLanguage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertFalse(names.any { it == "fridge" || it == "window" || it == "seat" })
    }

    @Test
    fun plainAndroidCommandsAreOfferedWithoutTheCarLinkButCarCommandsAreNot() {
        val off = names(assistantTools(vehicleControlEnabled = false, conversation = true))
        // The media volume is a plain Android call: offered (to set and to read) whatever the switch says.
        assertTrue("audio" in off)
        assertTrue("get_volume" in off)
        // Everything that reaches the car's systems is not declared at all: no family but audio, and only the volume in it.
        assertFalse(off.any { it == "seat" || it == "ac" || it == "dashcam" || it == "window" })
        val audio = assistantTools(vehicleControlEnabled = false, conversation = true)
            .flatMap { it.functionDeclarations.orEmpty() }.single { it.name == "audio" }
        assertEquals(setOf("value"), audio.parameters?.properties?.keys)
        assertFalse(audio.description.contains("mediaMute"))
        assertFalse("read_car" in off)
        assertFalse("passenger_screen" in off)
        val on = names(assistantTools(vehicleControlEnabled = true, conversation = true))
        assertTrue(listOf("audio", "read_car", "passenger_screen", "seat", "ac", "window", "dashcam").all { it in on })
        // No flat per-command function is left.
        assertFalse(on.any { it.contains('_') && it.substringBefore('_') in setOf("seat", "ac", "window", "audio", "fridge", "dashcam") })
    }

    @Test
    fun theThreeSensorReadersAreOneToolAndTheOldNamesAreGone() {
        val on = names(assistantTools(vehicleControlEnabled = true, conversation = true))
        assertEquals(1, on.count { it == "read_car" })
        for (old in listOf("get_tyre_pressure", "get_outside_temperature", "get_air_quality")) assertFalse(old, old in on)
    }

    @Test
    fun vehicleCommandsOnlyWhenArmed() {
        assertTrue("fridge" in names(assistantTools(vehicleControlEnabled = true)))
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
    fun theClusterScreenNeedsItsSwitchAndArmedVehicleControlInAConversation() {
        val tool = "cluster_screen"
        // Never without its switch, armed or not.
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = true, conversation = true)))
        // Not without vehicle control armed.
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = false, conversation = true, clusterScreen = true)))
        // Not in a one-shot request.
        assertFalse(tool in names(assistantTools(vehicleControlEnabled = true, conversation = false, clusterScreen = true)))
        assertTrue(tool in names(assistantTools(vehicleControlEnabled = true, conversation = true, clusterScreen = true)))
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

    @Test
    fun aFixedReplyLanguageIsSaidAsAFirmRuleAtTheStartAndAgainAtTheEnd() {
        for ((language, name) in listOf(AssistantLanguage.ARABIC to "Arabic", AssistantLanguage.ENGLISH to "English", AssistantLanguage.RUSSIAN to "Russian", AssistantLanguage.POLISH to "Polish")) {
            val prompt = voiceAssistantSystemPrompt(language, conversation = true)
            assertTrue(prompt.contains("Always reply in $name."))
            assertTrue(prompt.endsWith("ask the user in $name to say it again."))
            val shortPrompt = voiceAssistantSystemPrompt(language, conversation = false)
            assertTrue(shortPrompt.contains("every reply is in $name"))
        }
        val auto = voiceAssistantSystemPrompt(AssistantLanguage.AUTO, conversation = true)
        assertFalse(auto.contains("Always reply in"))
        assertFalse(auto.contains("Remember: every reply"))
    }

    @Test
    fun aReplyInTheWrongLanguageIsRecognisedByItsLetters() {
        assertEquals(true, ReplyLanguage.matches(AssistantLanguage.ARABIC, "تمام، فتحت النافذة. BYD"))
        assertEquals(false, ReplyLanguage.matches(AssistantLanguage.ARABIC, "Son las nueve y nueve minutos de la noche"))
        assertEquals(true, ReplyLanguage.matches(AssistantLanguage.ENGLISH, "It is nine past one, my friend."))
        assertEquals(false, ReplyLanguage.matches(AssistantLanguage.ENGLISH, "الساعة الآن واحدة"))
        assertEquals(false, ReplyLanguage.matches(AssistantLanguage.ENGLISH, "Son las nueve, cielo ¿sí?"))
        assertNull(ReplyLanguage.matches(AssistantLanguage.AUTO, "anything at all"))
        assertNull(ReplyLanguage.matches(AssistantLanguage.ARABIC, "..."))
    }

    @Test
    fun aRussianReplyIsRecognisedByItsLetters() {
        assertEquals(true, ReplyLanguage.matches(AssistantLanguage.RUSSIAN, "Хорошо, окно открыто. BYD"))
        assertEquals(false, ReplyLanguage.matches(AssistantLanguage.RUSSIAN, "Okay, the window is open."))
        assertEquals(false, ReplyLanguage.matches(AssistantLanguage.RUSSIAN, "تمام، فتحت النافذة"))
        // Russian is not English, and not Arabic.
        assertEquals(false, ReplyLanguage.matches(AssistantLanguage.ENGLISH, "Сейчас час дня"))
        assertEquals(false, ReplyLanguage.matches(AssistantLanguage.ARABIC, "Сейчас час дня"))
        assertNull(ReplyLanguage.matches(AssistantLanguage.RUSSIAN, "..."))
    }

    @Test
    fun aPolishReplyIsOnlyRuledOutByAnotherScript() {
        assertEquals(true, ReplyLanguage.matches(AssistantLanguage.POLISH, "Dobrze, okno jest otwarte. BYD"))
        // A short Polish reply may have no accented letter at all, so Latin text is never proved wrong.
        assertEquals(true, ReplyLanguage.matches(AssistantLanguage.POLISH, "Dobrze."))
        assertEquals(false, ReplyLanguage.matches(AssistantLanguage.POLISH, "Хорошо, окно открыто"))
        assertEquals(false, ReplyLanguage.matches(AssistantLanguage.POLISH, "تمام، فتحت النافذة"))
        // Polish is not English: its accented letters show.
        assertEquals(false, ReplyLanguage.matches(AssistantLanguage.ENGLISH, "Zamknąłem okno"))
        assertNull(ReplyLanguage.matches(AssistantLanguage.POLISH, "..."))
    }

    @Test
    fun theAutomaticLanguageNamesRussianAmongTheOnesItFollows() {
        val auto = voiceAssistantSystemPrompt(AssistantLanguage.AUTO)
        assertTrue(auto, auto.contains("English, Arabic, Russian or Polish"))
        assertTrue(auto, auto.contains("speaks Arabic (in any dialect), Russian, Polish and English"))
    }

    @Test
    fun aFixedRussianAssistantIsNotToldWhichArabicToSpeak() {
        val russian = voiceAssistantSystemPrompt(AssistantLanguage.RUSSIAN, dialect = ArabicDialect.GULF)
        assertFalse(russian.contains("Gulf Arabic"))
    }

    @Test
    fun theMusicLineIsInThePromptOnlyWhenAskedFor() {
        val off = voiceAssistantSystemPrompt(AssistantLanguage.AUTO, conversation = true)
        val on = voiceAssistantSystemPrompt(AssistantLanguage.AUTO, conversation = true, musicHint = true)
        assertFalse(off.contains("Music may be playing"))
        assertTrue(on.contains("Music may be playing in the car"))
        assertTrue(on.contains("Sung words and other music are not the driver speaking to you"))
        assertTrue(on.contains("answer only when a person speaks to you"))
    }
}

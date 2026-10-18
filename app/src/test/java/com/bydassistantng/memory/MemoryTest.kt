package com.bydassistantng.memory

import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.assistantTools
import com.bydassistantng.gemini.voiceAssistantSystemPrompt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFactsTest {
    private var counter = 0
    private fun id() = "id${counter++}"
    private fun fact(text: String, at: Long = 1) = MemoryFact("f-$text", text, at)

    @Test
    fun aFactIsTidiedAndAdded() {
        val (facts, outcome) = MemoryFacts.add(emptyList(), "  Home   is\n in Muscat ", 5, ::id)
        assertEquals(listOf("Home is in Muscat"), facts.map { it.text })
        assertTrue(outcome is AddOutcome.Added)
    }

    @Test
    fun theSameFactIsNotAddedTwiceWhateverTheCase() {
        val start = listOf(fact("Home is in Muscat"))
        val (facts, outcome) = MemoryFacts.add(start, "home is in muscat", 5, ::id)
        assertEquals(start, facts)
        assertTrue(outcome is AddOutcome.AlreadyKnown)
    }

    @Test
    fun anEmptyFactIsRefused() {
        assertEquals(AddOutcome.Empty, MemoryFacts.add(emptyList(), "   ", 5, ::id).second)
    }

    @Test
    fun theListIsCapped() {
        val full = (1..MemoryLimits.MAX_FACTS).map { fact("fact $it") }
        val (facts, outcome) = MemoryFacts.add(full, "one more", 5, ::id)
        assertEquals(full, facts)
        assertEquals(AddOutcome.Full, outcome)
    }

    @Test
    fun aLongFactIsCutVisibly() {
        val (facts, _) = MemoryFacts.add(emptyList(), "x".repeat(500), 5, ::id)
        assertEquals(MemoryLimits.MAX_FACT_CHARS, facts.single().text.length)
        assertTrue(facts.single().text.endsWith("…"))
    }

    @Test
    fun forgettingRemovesEveryFactThatContainsTheWords() {
        val start = listOf(fact("Home address: Al Khuwair"), fact("Work address: Qurum"), fact("Prefers Spotify"))
        val (kept, removed) = MemoryFacts.forget(start, "ADDRESS")
        assertEquals(2, removed)
        assertEquals(listOf("Prefers Spotify"), kept.map { it.text })
    }

    @Test
    fun aTooShortWordForgetsNothing() {
        val start = listOf(fact("Home address: Al Khuwair"))
        val (kept, removed) = MemoryFacts.forget(start, "a")
        assertEquals(0, removed)
        assertEquals(start, kept)
    }

    @Test
    fun thePromptSectionListsTheFactsOrIsNull() {
        assertNull(MemoryFacts.promptSection(emptyList()))
        val text = MemoryFacts.promptSection(listOf(fact("Home: Muscat."), fact("Likes jazz")))!!
        assertTrue(text.contains("Home: Muscat; Likes jazz"))
    }
}

class RecentExchangesTest {
    @Test
    fun onlyTheLastFewAreKeptNewestLast() {
        var list = emptyList<Exchange>()
        for (i in 1..5) list = RecentExchanges.record(list, "request $i", "reply $i", emptyList(), now = i * 1000L)
        assertEquals(listOf("request 3", "request 4", "request 5"), list.map { it.user })
    }

    @Test
    fun anExchangeWithNothingSaidIsNotKept() {
        assertTrue(RecentExchanges.record(emptyList(), "  ", "hello", emptyList(), 1).isEmpty())
    }

    @Test
    fun justEndingTheConversationIsNotKept() {
        val before = listOf(Exchange("turn on the fridge", "Done", listOf("fridge_power(on)"), 1))
        val after = RecentExchanges.record(before, "okay thanks", "Goodbye", listOf("end_conversation"), 2)
        assertEquals(before, after)
    }

    @Test
    fun onlyRecentExchangesCountAsContext() {
        val now = 100 * 60_000L
        val list = listOf(
            Exchange("old", "x", emptyList(), now - MemoryLimits.SHORT_TERM_WINDOW_MS - 1),
            Exchange("recent", "y", emptyList(), now - 60_000),
        )
        assertEquals(listOf("recent"), RecentExchanges.fresh(list, now).map { it.user })
    }

    @Test
    fun thePromptSectionCarriesWhatWasSaidAndDone() {
        val text = RecentExchanges.promptSection(
            listOf(Exchange("turn on the driver seat ventilation to high", "Driver seat ventilation is on high.", listOf("seat_driver_ventilation(high)"), 1)),
        )!!
        assertTrue(text.contains("turn on the driver seat ventilation to high"))
        assertTrue(text.contains("seat_driver_ventilation(high)"))
        assertTrue(text.contains("Driver seat ventilation is on high."))
        assertNull(RecentExchanges.promptSection(emptyList()))
    }

    @Test
    fun callsAreWrittenCompactly() {
        assertEquals("open_app", RecentExchanges.describeCall("open_app", emptyMap()))
        assertEquals("fridge_power(on)", RecentExchanges.describeCall("fridge_power", mapOf("value" to "on")))
        assertEquals("seat.driver.heating(high)", RecentExchanges.describeCall("seat", linkedMapOf("setting" to "driver.heating", "value" to "high")))
        assertEquals("play_media(app=Spotify, query=jazz)", RecentExchanges.describeCall("play_media", linkedMapOf("app" to "Spotify", "query" to "jazz")))
    }

    @Test
    fun theStoredFormRoundTrips() {
        val json = Json { ignoreUnknownKeys = true }
        val list = listOf(Exchange("a", "b", listOf("x(1)"), 7))
        val serializer = ListSerializer(Exchange.serializer())
        assertEquals(list, json.decodeFromString(serializer, json.encodeToString(serializer, list)))
        val facts = listOf(MemoryFact("1", "Home: Muscat", 3))
        val factSerializer = ListSerializer(MemoryFact.serializer())
        assertEquals(facts, json.decodeFromString(factSerializer, json.encodeToString(factSerializer, facts)))
    }
}

class MemoryToolsTest {
    private fun call(name: String, vararg args: Pair<String, String>) =
        GeminiFunctionCall(name = name, args = buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    @Test
    fun theRememberCallCarriesTheFact() {
        assertEquals("Home: Muscat", RememberTool.fact(call("remember", "fact" to " Home: Muscat ")))
        assertNull(RememberTool.fact(call("remember", "fact" to "  ")))
        assertNull(RememberTool.fact(call("remember")))
    }

    @Test
    fun theAnswersSayWhatHappened() {
        val added = RememberTool.answer(AddOutcome.Added(MemoryFact("1", "Home: Muscat", 1)))
        assertEquals("ok", (added["status"] as JsonPrimitive).content)
        assertEquals("error", (RememberTool.answer(AddOutcome.Full)["status"] as JsonPrimitive).content)
        assertEquals("ok", (ForgetMemoryTool.answer(2)["status"] as JsonPrimitive).content)
        assertEquals("error", (ForgetMemoryTool.answer(0)["status"] as JsonPrimitive).content)
    }

    @Test
    fun theToolsAreOfferedOnlyInAConversationWithLongTermMemoryOn() {
        fun names(tools: List<com.bydassistantng.gemini.GeminiTool>) = tools.flatMap { it.functionDeclarations.orEmpty() }.map { it.name }
        assertFalse("remember" in names(assistantTools(vehicleControlEnabled = false, conversation = true)))
        assertFalse("remember" in names(assistantTools(vehicleControlEnabled = false, conversation = false, memoryTools = true)))
        val on = names(assistantTools(vehicleControlEnabled = false, conversation = true, memoryTools = true))
        assertTrue("remember" in on && "forget_memory" in on)
    }

    @Test
    fun thePromptCarriesMemoryOnlyWhenThereIsSome() {
        fun prompt(memory: MemoryContext) = voiceAssistantSystemPrompt(AssistantLanguage.AUTO, conversation = true, dialect = ArabicDialect.MATCH, memory = memory)
        val plain = prompt(MemoryContext())
        assertFalse(plain.contains("remember"))
        assertFalse(plain.contains("most recent exchanges"))

        val withFacts = prompt(MemoryContext(tools = true, facts = listOf(MemoryFact("1", "Home: Muscat", 1))))
        assertTrue(withFacts.contains("Home: Muscat"))
        assertTrue(withFacts.contains("call remember"))

        val withRecent = prompt(MemoryContext(recent = listOf(Exchange("open the sunroof", "Opening it.", listOf("sunroof(open)"), 1))))
        assertTrue(withRecent.contains("open the sunroof"))
        assertFalse(withRecent.contains("call remember"))
    }

    @Test
    fun aNoteAboutGreetingsIsKeptToTheAutomaticGreeting() {
        val section = MemoryFacts.promptSection(listOf(MemoryFact("1", "When the driver gets in: say good day, captain", 0)))!!
        assertTrue(section, section.contains("applies only to the automatic greeting message"))
        assertTrue(section, section.contains("when the driver speaks to you first, answer what they said"))
    }

    @Test
    fun theLastExchangesAreContextAndNotAScriptToCopy() {
        val section = RecentExchanges.promptSection(listOf(Exchange("hello", "good day, captain", emptyList(), 0)))!!
        assertTrue(section, section.contains("do not copy your earlier replies"))
    }

    @Test
    fun theRememberToolMarksAGreetingNoteSoItIsUsedOnlyForTheGreeting() {
        assertTrue(RememberTool.declaration.description.contains("When the driver gets in:"))
    }
}

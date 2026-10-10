package com.bydassistantng.media

import com.bydassistantng.gemini.GeminiFunctionCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlayMediaToolTest {
    private fun call(vararg args: Pair<String, String>) =
        GeminiFunctionCall(name = PlayMediaTool.FUNCTION_NAME, args = JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    @Test
    fun anAppAndASearchPhraseAreEnough() {
        val request = PlayRequest.from(call("app" to "Spotify", "query" to "Hotel California Eagles"))
        assertEquals(PlayRequest("Spotify", "Hotel California Eagles", MediaKind.ANY), request)
    }

    @Test
    fun withoutAPhraseOrAnAppThereIsNothingToAsk() {
        assertNull(PlayRequest.from(call("app" to "YouTube")))
        assertNull(PlayRequest.from(call("app" to "YouTube", "query" to "   ")))
        assertNull(PlayRequest.from(call("query" to "moody song")))
    }

    @Test
    fun theKindSelectsWhatTheAppLooksFor() {
        assertEquals(MediaKind.VIDEO, PlayRequest.from(call("app" to "YouTube", "query" to "x", "kind" to "video"))?.kind)
        assertEquals(MediaKind.ARTIST, PlayRequest.from(call("app" to "Spotify", "query" to "x", "kind" to " Artist "))?.kind)
        assertEquals(MediaKind.ANY, PlayRequest.from(call("app" to "Spotify", "query" to "x", "kind" to "whatever"))?.kind)
    }

    @Test
    fun overlongTextIsCut() {
        val request = PlayRequest.from(call("app" to "Spotify", "query" to "a".repeat(5_000)))
        assertNotNull(request)
        assertEquals(200, request!!.query.length)
    }

    @Test
    fun everyKindHasADistinctFocusType() {
        assertEquals(MediaKind.entries.size, MediaKind.entries.map { it.focus }.toSet().size)
        assertEquals(MediaKind.entries.size, MediaKind.entries.map { it.word }.toSet().size)
    }
}

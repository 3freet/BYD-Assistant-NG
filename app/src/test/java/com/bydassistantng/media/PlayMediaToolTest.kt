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
    fun theUsersOwnLibraryNeedsNoSearchPhrase() {
        assertEquals(
            PlayRequest("Spotify", "", MediaKind.ANY, PlaySource.FAVORITES),
            PlayRequest.from(call("app" to "Spotify", "source" to "favorites")),
        )
        assertEquals(
            PlayRequest("Spotify", "", MediaKind.ANY, PlaySource.MY_PLAYLISTS),
            PlayRequest.from(call("app" to "Spotify", "source" to "my_playlists")),
        )
        assertEquals(
            PlayRequest("Spotify", "road trip", MediaKind.PLAYLIST, PlaySource.MY_PLAYLISTS),
            PlayRequest.from(call("app" to "Spotify", "query" to "road trip", "kind" to "playlist", "source" to "my_playlists")),
        )
    }

    @Test
    fun theLibraryStillNeedsAnApp() {
        assertNull(PlayRequest.from(call("source" to "favorites")))
        assertNull(PlayRequest.from(call("app" to "  ", "source" to "my_playlists")))
    }

    @Test
    fun aSearchWithoutAPhraseIsStillNothingToAsk() {
        assertNull(PlayRequest.from(call("app" to "Spotify", "source" to "search")))
        assertNull(PlayRequest.from(call("app" to "Spotify", "source" to "something unknown")))
    }

    @Test
    fun theSourceIsUnderstoodWhateverTheWordingOfTheModel() {
        assertEquals(PlaySource.FAVORITES, PlaySource.from(" Favourites "))
        assertEquals(PlaySource.FAVORITES, PlaySource.from("liked songs"))
        assertEquals(PlaySource.FAVORITES, PlaySource.from("LIKED-SONGS"))
        assertEquals(PlaySource.MY_PLAYLISTS, PlaySource.from("my playlists"))
        assertEquals(PlaySource.MY_PLAYLISTS, PlaySource.from("playlist"))
        assertEquals(PlaySource.SEARCH, PlaySource.from(""))
        assertEquals(PlaySource.SEARCH, PlaySource.from("nonsense"))
    }

    @Test
    fun theDeclarationOffersTheSourcesAndOnlyRequiresTheApp() {
        val parameters = PlayMediaTool.declaration.parameters!!
        assertEquals(listOf("app"), parameters.required)
        assertEquals(PlaySource.entries.map { it.word }, parameters.properties!!["source"]!!.enum)
        assertEquals(PlaySource.entries.size, PlaySource.entries.map { it.word }.toSet().size)
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

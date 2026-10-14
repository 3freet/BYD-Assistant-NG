package com.bydassistantng.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstResultTest {
    private fun node(pkg: String, id: String = "", desc: String = "", clickable: Boolean = true, b: String, text: String = ""): String {
        val (l, t, r, bt) = Regex("""\d+""").findAll(b).map { it.value }.toList()
        return """<node index="0" text="$text" resource-id="$id" class="x" package="$pkg" content-desc="$desc" clickable="$clickable" bounds="[$l,$t][$r,$bt]" />"""
    }

    private fun dump(vararg nodes: String) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">${nodes.joinToString("")}</hierarchy>"""

    private val sp = "com.spotify.music"
    private val yt = "com.google.android.youtube"

    private fun spotifyRow(top: Int, menu: String?) = buildList {
        add(node(sp, "$sp:id/row_root", b = "16,$top,2544,${top + 128}"))
        if (menu != null) add(node(sp, "$sp:id/context_menu_button", desc = menu, b = "2352,${top + 16},2448,${top + 112}"))
        add(node(sp, "$sp:id/add_button", desc = "Add to My Library", b = "2448,${top + 16},2544,${top + 112}"))
    }.toTypedArray()

    @Test
    fun readsTheNodesOfADump() {
        val nodes = ScreenDump.parse(dump(node(sp, "$sp:id/row_root", desc = "a", b = "16,336,2544,464")))
        assertEquals(1, nodes.size)
        assertEquals(ScreenNode(sp, "$sp:id/row_root", "", "a", true, 16, 336, 2544, 464), nodes.single())
        assertEquals(1280, nodes.single().centerX)
        assertEquals(400, nodes.single().centerY)
    }

    @Test
    fun somethingThatIsNotADumpGivesNothing() {
        assertTrue(ScreenDump.parse("").isEmpty())
        assertTrue(ScreenDump.parse("ERROR: could not get idle state").isEmpty())
        assertTrue(ScreenDump.parse("<hierarchy><node bounds=").isEmpty())
    }

    @Test
    fun spotifyPressesTheLeftPartOfTheFirstRow() {
        val nodes = ScreenDump.parse(dump(*spotifyRow(336, "More options for song Imagine - Remastered 2010"), *spotifyRow(464, "More options for song Imagine")))
        val point = FirstResult.forPackage(sp, nodes, MediaKind.ANY)!!
        assertEquals(16 + (2544 - 16) / 4, point.x)
        assertEquals(400, point.y)
        assertEquals("Imagine - Remastered 2010", point.label)
    }

    @Test
    fun forASongSpotifySkipsRowsThatAreNotSongs() {
        val nodes = ScreenDump.parse(dump(*spotifyRow(336, null), *spotifyRow(464, "More options for song Wonderwall")))
        val point = FirstResult.forPackage(sp, nodes, MediaKind.SONG)!!
        assertEquals(528, point.y)
        assertEquals("Wonderwall", point.label)
        // Without a kind, the very first row is the top result.
        assertEquals(400, FirstResult.forPackage(sp, nodes, MediaKind.ANY)!!.y)
    }

    @Test
    fun nothingIsPressedUntilTheResultsAreThere() {
        val onlySearchBox = ScreenDump.parse(dump(node(sp, "$sp:id/query", b = "112,153,2448,198")))
        assertNull(FirstResult.forPackage(sp, onlySearchBox, MediaKind.ANY))
        assertNull(FirstResult.forPackage(sp, emptyList(), MediaKind.ANY))
    }

    @Test
    fun otherAppsOnScreenAreIgnored() {
        val nodes = ScreenDump.parse(dump(node("com.other.app", "$sp:id/row_root", b = "16,336,2544,464")))
        assertNull(FirstResult.forPackage(sp, nodes, MediaKind.ANY))
    }

    @Test
    fun youtubePrefersARegularVideoOverAShort() {
        val nodes = ScreenDump.parse(
            dump(
                node(yt, desc = "How to change a tire in 60 seconds, 2.3 million views, Dad, 3 years ago - play Short", b = "32,243,428,943"),
                node(yt, desc = "How to Change a Tire - 2 minutes, 1 second - Go to channel Cars.com - 700 thousand views - 8 years ago - play video", b = "32,991,2544,1224"),
            ),
        )
        val point = FirstResult.forPackage(yt, nodes, MediaKind.VIDEO)!!
        assertEquals(1288, point.x)
        assertEquals(1107, point.y)
    }

    @Test
    fun youtubeFallsBackToAShortWhenThatIsAllThereIs() {
        val nodes = ScreenDump.parse(dump(node(yt, desc = "A trick, 1 million views, Someone, 1 year ago - play Short", b = "32,243,428,943")))
        assertNotNull(FirstResult.forPackage(yt, nodes, MediaKind.ANY))
    }

    private val queryBox = "$yt:id/search_query"
    private val firstVideo = "Relaxing Piano Music: Romantic Music - 3 hours, 36 minutes - Soothing Relaxation - 218 million views - 9 years ago - play video"

    @Test
    fun youtubeNeverPressesTheMiniPlayerOfAnEarlierVideo() {
        // A video minimized earlier leaves a small "Play video" button on the results page, ahead of the results.
        val nodes = ScreenDump.parse(
            dump(
                node(yt, "$yt:id/modern_miniplayer_overlay_action_button", desc = "Play video", b = "1900,843,1996,939"),
                node(yt, desc = firstVideo, b = "32,227,2544,669"),
            ),
        )
        val point = FirstResult.forPackage(yt, nodes, MediaKind.VIDEO)!!
        assertEquals(448, point.y)
        assertEquals("Relaxing Piano Music: Romantic Music - 3 hours", point.label)
    }

    @Test
    fun youtubeOnlyPressesResultsThatAreForTheQueryAsked() {
        val stale = ScreenDump.parse(dump(node(yt, queryBox, b = "112,128,2176,195", text = "blinding lights", clickable = true), node(yt, desc = firstVideo, b = "32,227,2544,669")))
        assertNull(FirstResult.forPackage(yt, stale, MediaKind.VIDEO, "relaxing piano music"))
        val fresh = ScreenDump.parse(dump(node(yt, queryBox, b = "112,128,2176,195", text = "Relaxing piano music"), node(yt, desc = firstVideo, b = "32,227,2544,669")))
        assertNotNull(FirstResult.forPackage(yt, fresh, MediaKind.VIDEO, "relaxing piano music"))
        // A page that is not a results page at all (no query box) is not the results either.
        val watchPage = ScreenDump.parse(dump(node(yt, desc = firstVideo, b = "32,227,2544,669")))
        assertNull(FirstResult.forPackage(yt, watchPage, MediaKind.VIDEO, "relaxing piano music"))
        // Without a query nothing is checked.
        assertNotNull(FirstResult.forPackage(yt, watchPage, MediaKind.VIDEO))
    }

    @Test
    fun youtubePlaylistsAndOtherDescriptionsAreNotResultsToPlay() {
        val nodes = ScreenDump.parse(dump(node(yt, desc = "Mix - The Weeknd - Starboy - Daft Punk, and more - 50 videos", b = "32,701,2544,1153")))
        assertNull(FirstResult.forPackage(yt, nodes, MediaKind.VIDEO))
    }

    @Test
    fun onlyKnownAppsHaveARule() {
        assertTrue(FirstResult.hasRule(sp))
        assertTrue(FirstResult.hasRule(yt))
        assertEquals(false, FirstResult.hasRule("com.example.music"))
        assertNull(FirstResult.forPackage("com.example.music", emptyList(), MediaKind.ANY))
    }
}

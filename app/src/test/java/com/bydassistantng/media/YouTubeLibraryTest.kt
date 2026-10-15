package com.bydassistantng.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLibraryTest {
    private val yt = "com.google.android.youtube"

    private fun node(desc: String = "", clickable: Boolean = true, b: String, pkg: String = yt): String {
        val (l, t, r, bt) = Regex("""\d+""").findAll(b).map { it.value }.toList()
        val safe = desc.replace("&", "&amp;").replace("\"", "&quot;")
        return """<node index="0" text="" resource-id="" class="x" package="$pkg" content-desc="$safe" clickable="$clickable" bounds="[$l,$t][$r,$bt]" />"""
    }

    private fun dump(vararg nodes: String) =
        ScreenDump.parse("""<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">${nodes.joinToString("")}</hierarchy>""")

    /** The playlists feed: system playlists first, then the user's own; each row with its action menu beside it. */
    private val feed = dump(
        node("Recently added", b = "32,364,306,428"),
        node("Playlist - Liked videos - Private - 5 videos - Updated 3 days ago", b = "0,444,2560,688"),
        node("Action menu", b = "2510,470,2546,506"),
        node("Playlist - Watch later - Private - 11 videos", b = "0,688,2560,932"),
        node("Playlist - Evening Drive - Some Owner - 149 videos", b = "0,932,2560,1176"),
        node("Playlist - Gym - Warm up - Some Owner - 20 videos - Updated yesterday", b = "0,1176,2560,1420"),
        node("Create new playlist", b = "24,1200,2536,1224"),
    )

    @Test
    fun theFeedGivesTheRowsWithTheirTitles() {
        val rows = YouTubeLibrary.rows(feed, yt)
        assertEquals(listOf("Liked videos", "Watch later", "Evening Drive", "Gym - Warm up"), rows.map { it.title })
        assertEquals(listOf(true, true, false, false), rows.map { it.marked })
    }

    @Test
    fun withoutANameTheFirstPlaylistOfTheUsersOwnIsTaken() {
        val point = YouTubeLibrary.target(feed, yt, "")!!
        assertEquals("Evening Drive", point.label)
        assertEquals(640, point.x)
        assertEquals(1054, point.y)
    }

    @Test
    fun aNamedPlaylistIncludingTheOnesEveryAccountHasIsFound() {
        assertEquals("Watch later", YouTubeLibrary.target(feed, yt, "watch later")!!.label)
        assertEquals("Liked videos", YouTubeLibrary.target(feed, yt, "Liked videos")!!.label)
        assertEquals("Gym - Warm up", YouTubeLibrary.target(feed, yt, "gym")!!.label)
        assertNull(YouTubeLibrary.target(feed, yt, "Cooking"))
    }

    @Test
    fun titlesWithADashStayWhole() {
        assertEquals("Gym - Warm up", YouTubeLibrary.titleOf("Gym - Warm up - Some Owner - 20 videos - Updated yesterday"))
        assertEquals("Mix", YouTubeLibrary.titleOf("Mix"))
        assertEquals("Road trip", YouTubeLibrary.titleOf("Road trip - Owner - 1 video"))
        // No count (another language): up to the first dash.
        assertEquals("قائمتي", YouTubeLibrary.titleOf("قائمتي - المالك - ٣٢ فيديو"))
    }

    @Test
    fun otherScreensGiveNoRows() {
        assertTrue(YouTubeLibrary.rows(dump(node("Play all", b = "584,240,1153,560")), yt).isEmpty())
        assertNull(YouTubeLibrary.target(emptyList(), yt, ""))
        assertNull(YouTubeLibrary.target(dump(node("Playlist - X - Y - 1 video", b = "0,444,2560,688", pkg = "com.other")), yt, ""))
    }

    @Test
    fun playAllIsThePlayControlOfAPlaylistPage() {
        val nodes = dump(
            node("Play all", b = "584,240,1153,560"),
            node("Play all", b = "1185,434,1395,514"),
            node("Play video", b = "1900,843,1996,939"),
        )
        val button = FirstResult.playButtonOnPage(nodes, yt)!!
        assertEquals((584 + 1153) / 2, button.x)
        assertEquals(400, button.y)
        assertNotNull(FirstResult.playButtonOnPage(dump(node("Play all", b = "10,10,100,100")), yt))
    }

    @Test
    fun searchLinksCarryTheEncodedPhraseAndTheVideosOnlyFilter() {
        assertEquals(
            "https://www.youtube.com/results?search_query=relaxing+piano+music&sp=EgIQAQ%3D%3D",
            YouTubeLibrary.searchUrl("relaxing piano music"),
        )
        assertEquals(
            "https://www.youtube.com/results?search_query=rock+%26+roll+%23%231%3F&sp=EgIQAQ%3D%3D",
            YouTubeLibrary.searchUrl("rock & roll ##1?"),
        )
    }

    @Test
    fun libraryLinksExistForFavoritesAndPlaylistsOnly() {
        assertEquals("https://www.youtube.com/playlist?list=LL", YouTubeLibrary.linkFor(PlaySource.FAVORITES))
        assertEquals("https://www.youtube.com/feed/playlists", YouTubeLibrary.linkFor(PlaySource.MY_PLAYLISTS))
        assertNull(YouTubeLibrary.linkFor(PlaySource.SEARCH))
    }

    @Test
    fun theTextMatchIgnoresCaseAndPunctuation() {
        assertTrue(TextMatch.same("Relaxing piano  music!", "relaxing piano music"))
        assertTrue(TextMatch.same("relaxing piano music 2024", "relaxing piano music"))
        assertEquals(false, TextMatch.same("blinding lights", "relaxing piano music"))
        assertEquals(false, TextMatch.same("", "relaxing piano music"))
    }

    // ── a video for a phrase (the cluster has no touch, so the first result is chosen without pressing it) ──

    @Test
    fun theFirstVideoOfTheDesktopResultsPageIsTakenWithItsTitle() {
        val page = """{"contents":{"sectionListRenderer":{"contents":[{"adSlotRenderer":{"videoId":"ADADADADAD1"}},""" +
            """{"videoRenderer":{"videoId":"dQw4w9WgXcQ","thumbnail":{"thumbnails":[]},"title":{"runs":[{"text":"Rick Astley - Never Gonna Give You Up \u0026 more"}],"accessibility":{}}}},""" +
            """{"videoRenderer":{"videoId":"3BFTio5296w","title":{"runs":[{"text":"Another"}]}}}]}}}"""
        val hit = YouTubeLibrary.firstVideo(page)!!
        assertEquals("dQw4w9WgXcQ", hit.id)
        assertEquals("Rick Astley - Never Gonna Give You Up & more", hit.title)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", YouTubeLibrary.watchUrl(hit.id))
    }

    @Test
    fun theMobilePageWithHexEscapesIsReadToo() {
        val page = """var ytInitialData = '\x7b\x22watchEndpoint\x22:\x7b\x22videoId\x22:\x22yPYZpwSpKmA\x22,\x22playlistId\x22:1\x7d\x7d';"""
        assertEquals("yPYZpwSpKmA", YouTubeLibrary.firstVideo(page)!!.id)
    }

    @Test
    fun aPageWithNoVideoGivesNothing() {
        assertNull(YouTubeLibrary.firstVideo("<html>consent</html>"))
        assertNull(YouTubeLibrary.firstVideo(""))
    }

    @Test
    fun aPastedWatchLinkNamesItsVideo() {
        assertEquals("dQw4w9WgXcQ", YouTubeLibrary.videoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeLibrary.videoIdOf("https://youtu.be/dQw4w9WgXcQ?t=5"))
        assertEquals("dQw4w9WgXcQ", YouTubeLibrary.videoIdOf("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertNull(YouTubeLibrary.videoIdOf("never gonna give you up"))
        assertNull(YouTubeLibrary.videoIdOf("https://example.com/watch?v=dQw4w9WgXcQ"))
    }
}

package com.bydassistantng.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyLibraryTest {
    private val sp = "com.spotify.music"

    private fun node(
        text: String = "",
        desc: String = "",
        clickable: Boolean = false,
        b: String,
        pkg: String = sp,
    ): String {
        val (l, t, r, bt) = Regex("""\d+""").findAll(b).map { it.value }.toList()
        val safeText = text.replace("&", "&amp;").replace("\"", "&quot;")
        val safeDesc = desc.replace("&", "&amp;").replace("\"", "&quot;")
        return """<node index="0" text="$safeText" resource-id="" class="x" package="$pkg" content-desc="$safeDesc" clickable="$clickable" bounds="[$l,$t][$r,$bt]" />"""
    }

    private fun dump(vararg nodes: String) =
        ScreenDump.parse("""<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">${nodes.joinToString("")}</hierarchy>""")

    /** A row as the library page draws it: a clickable box holding the title, a subtitle and sometimes a label icon. */
    private fun row(top: Int, title: String, pinned: Boolean = false): Array<String> = buildList {
        add(node(clickable = true, b = "608,$top,1952,${top + 128}"))
        add(node(text = title, b = "784,${top + 22},1089,${top + 66}"))
        if (pinned) add(node(desc = "Pinned", b = "784,${top + 76},808,${top + 100}"))
        add(node(text = "Someone", b = "816,${top + 70},900,${top + 106}"))
    }.toTypedArray()

    /** The parts of the page that are clickable and hold text but are not rows. */
    private val chrome = arrayOf(
        node(desc = "Display options, current Sort Order: Recents", b = "608,326,1952,422"),
        node(clickable = true, b = "616,326,824,422"),
        node(text = "Recents", b = "696,356,792,392"),
        node(text = "Your Library", desc = "Your Library Heading. Scroll to top of list", clickable = true, b = "144,112,2336,208"),
    )

    private val library = dump(
        *chrome,
        *row(422, "Liked Songs", pinned = true),
        *row(582, "Evening Drive"),
        *row(742, "Your Top Songs 2025"),
        *row(902, "رحلة طويلة"),
    )

    @Test
    fun theListIsTheRowsThatLineUpNotTheSortControlOrTheHeading() {
        val rows = SpotifyLibrary.rows(library, sp)
        assertEquals(listOf("Liked Songs", "Evening Drive", "Your Top Songs 2025", "رحلة طويلة"), rows.map { it.title })
        assertEquals(listOf(true, false, false, false), rows.map { it.marked })
    }

    @Test
    fun withoutANameTheMostRecentPlaylistThatIsNotPinnedIsTaken() {
        val point = SpotifyLibrary.target(library, sp, "")!!
        assertEquals("Evening Drive", point.label)
        assertEquals(608 + (1952 - 608) / 4, point.x)
        assertEquals(582 + 64, point.y)
    }

    @Test
    fun aNamedPlaylistIsFoundWhateverTheCaseOrPunctuation() {
        assertEquals("Your Top Songs 2025", SpotifyLibrary.target(library, sp, "your top songs, 2025")!!.label)
        assertEquals("Evening Drive", SpotifyLibrary.target(library, sp, "evening drive")!!.label)
        assertEquals("رحلة طويلة", SpotifyLibrary.target(library, sp, "رحلة طويلة")!!.label)
    }

    @Test
    fun aPartOfTheNameIsEnough() {
        assertEquals("Your Top Songs 2025", SpotifyLibrary.target(library, sp, "top songs")!!.label)
        assertEquals("Evening Drive", SpotifyLibrary.target(library, sp, "my evening drive playlist")!!.label)
    }

    @Test
    fun aNameThatIsNotThereGivesNothingRatherThanTheWrongPlaylist() {
        assertNull(SpotifyLibrary.target(library, sp, "Workout Beats"))
    }

    @Test
    fun theBestFitWinsOverAPartialOne() {
        val nodes = dump(*chrome, *row(422, "Road Trip 2019"), *row(582, "Road Trip"), *row(742, "Trip"))
        assertEquals("Road Trip", SpotifyLibrary.target(nodes, sp, "road trip")!!.label)
    }

    @Test
    fun whenEveryRowIsMarkedTheFirstIsTaken() {
        val nodes = dump(*chrome, *row(422, "Liked Songs", pinned = true), *row(582, "Pinned One", pinned = true))
        assertEquals("Liked Songs", SpotifyLibrary.target(nodes, sp, "")!!.label)
    }

    @Test
    fun nothingIsPickedFromAScreenThatIsNotTheLibrary() {
        assertNull(SpotifyLibrary.target(emptyList(), sp, ""))
        assertNull(SpotifyLibrary.target(dump(node(text = "Liked Songs", b = "640,230,900,288")), sp, ""))
        assertNull(SpotifyLibrary.target(dump(*row(422, "Evening Drive").map { it.replace(sp, "com.other") }.toTypedArray()), sp, ""))
    }

    @Test
    fun onlySpotifyHasLibraryLinks() {
        assertEquals("spotify:collection:tracks", SpotifyLibrary.linkFor(sp, PlaySource.FAVORITES))
        assertEquals("spotify:collection:playlists", SpotifyLibrary.linkFor(sp, PlaySource.MY_PLAYLISTS))
        assertNull(SpotifyLibrary.linkFor(sp, PlaySource.SEARCH))
        assertNull(SpotifyLibrary.linkFor("com.google.android.youtube", PlaySource.FAVORITES))
    }

    @Test
    fun theLikedSongsPlayControlIsFoundByItsShuffleLabel() {
        val nodes = dump(
            node(desc = "Download Playlist: Liked Songs", clickable = true, b = "616,364,712,460"),
            node(desc = "Shuffle Play playlist", b = "1796,344,1932,480"),
            node(desc = "Play", clickable = true, b = "2432,1176,2512,1256"),
        )
        val button = FirstResult.playButtonOnPage(nodes, sp)!!
        assertEquals(1864, button.x)
        assertEquals(412, button.y)
        assertTrue(button.label.startsWith("Shuffle"))
        assertNotNull(FirstResult.playButtonOnPage(dump(node(desc = "Play playlist", b = "10,10,100,100")), sp))
        assertNull(FirstResult.playButtonOnPage(dump(node(desc = "Play something else", b = "10,10,100,100")), sp))
    }

    @Test
    fun namesAreComparedWithoutCaseMarksOrPunctuation() {
        assertEquals("rock n roll", TextMatch.normalize("  Rock 'n' Roll!! "))
        assertEquals(TextMatch.normalize("نَغْمَة"), TextMatch.normalize("نغمة"))
    }
}

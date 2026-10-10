package com.bydassistantng.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewHierarchyTest {
    // The shape of `dumpsys activity top`: bounds are relative to the parent, nesting is the indentation.
    private fun dump(pkg: String = "com.spotify.music", menuWidth: Int = 96) = """
      TASK 1 id=9
        ACTIVITY $pkg/.SpotifyMainActivity 7321fcd pid=100
          View Hierarchy:
            DecorView@1[SpotifyMainActivity]
              android.widget.FrameLayout{a V.E...... ........ 16,112-2544,1320}
                androidx.recyclerview.widget.RecyclerView{b VFED..... ........ 0,224-2528,1208 #7f0b0e9c app:id/search_content_recyclerview}
                  p.row{c VFE...CL. ........ 0,0-2528,128 #7f0b0e3e app:id/row_root}
                    p.Menu{d VFED..C.. ........ 2336,16-2432,112 #7f0b03b7 app:id/context_menu_button}
                  p.row{e VFE...CL. ........ 0,128-2528,256 #7f0b0e3e app:id/row_root}
                    p.Menu{f VFED..C.. ........ 2336,16-${2336 + menuWidth},112 #7f0b03b7 app:id/context_menu_button}
                  p.row{9 VFE...C.. ........ 0,256-2528,384 #7f0b0e3e app:id/row_root}
    """.trimIndent()

    @Test
    fun positionsAreTheSumOfTheAncestorsOffsets() {
        val rows = ViewHierarchy.parse(dump()).filter { it.resourceId.endsWith("row_root") }
        assertEquals(3, rows.size)
        assertEquals(listOf(336, 464, 592), rows.map { it.top })
        assertEquals(16, rows[0].left)
        assertEquals(2544, rows[0].right)
        assertEquals("com.spotify.music", rows[0].activityPackage)
    }

    @Test
    fun spotifyPressesTheLeftPartOfTheFirstRow() {
        val point = FirstResult.spotifyFromViews(ViewHierarchy.parse(dump()), MediaKind.ANY)!!
        assertEquals(16 + 2528 / 4, point.x)
        assertEquals(400, point.y)
    }

    @Test
    fun forASongTheFirstRowWithAMoreOptionsButtonIsChosen() {
        // Here only the second row has a real menu button (the first has none).
        val withoutFirstMenu = dump().lines().filterNot { it.contains("d VFED") }.joinToString("\n")
        val point = FirstResult.spotifyFromViews(ViewHierarchy.parse(withoutFirstMenu), MediaKind.SONG)!!
        assertEquals(528, point.y)
    }

    @Test
    fun aZeroSizedMenuDoesNotMakeASongRow() {
        val nodes = ViewHierarchy.parse(dump(menuWidth = 0).lines().filterNot { it.contains("d VFED") }.joinToString("\n"))
        assertNull(FirstResult.spotifyFromViews(nodes, MediaKind.SONG))
        assertNotNull(FirstResult.spotifyFromViews(nodes, MediaKind.ANY))
    }

    @Test
    fun anotherAppsWindowIsIgnored() {
        assertNull(FirstResult.spotifyFromViews(ViewHierarchy.parse(dump(pkg = "com.byd.mycar")), MediaKind.ANY))
        assertTrue(ViewHierarchy.parse("nothing here").isEmpty())
    }

    @Test
    fun rowsOutsideTheSearchListAreNotResults() {
        val homeScreen = """
            ACTIVITY com.spotify.music/.SpotifyMainActivity 1 pid=1
              View Hierarchy:
                p.Recycler{a VFED..... ........ 0,0-2528,900 #7f app:id/home_recyclerview}
                  p.row{b VFE...CL. ........ 0,0-2528,128 #7f app:id/row_root}
        """.trimIndent()
        assertNull(FirstResult.spotifyFromViews(ViewHierarchy.parse(homeScreen), MediaKind.ANY))
    }

    @Test
    fun youtubeIsTheOnlyAppThatNeedsUiAutomation() {
        assertTrue(FirstResult.needsUiAutomation(FirstResult.YOUTUBE))
        assertEquals(false, FirstResult.needsUiAutomation(FirstResult.SPOTIFY))
    }
}

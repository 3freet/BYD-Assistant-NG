package com.bydassistantng.media

import java.net.URLEncoder

/**
 * YouTube's side of "play X on YouTube", "play my favorites" and "play music from my playlists". All three go
 * through YouTube's own web links, which the app opens in whatever state it is in:
 *
 * - A search link opens the results for a phrase. The play-from-search request is not used: YouTube ignores it
 *   while it is already open (the old page stays and the wrong video gets pressed).
 * - `playlist?list=LL` is the Liked videos playlist, with a "Play all" control.
 * - `feed/playlists` lists the user's playlists (Liked videos and Watch later among them), each row described as
 *   "Playlist - <title> - <owner> - <n> videos"; a row opens the playlist's page, which has "Play all" too.
 *
 * The pages are read through the accessibility tree (see [UiPlayStarter]).
 */
object YouTubeLibrary {
    const val LIKED_LINK = "https://www.youtube.com/playlist?list=LL"
    const val PLAYLISTS_LINK = "https://www.youtube.com/feed/playlists"

    /** YouTube's "videos only" result filter: no channels, playlists, mixes or Shorts shelves to get in the way. */
    private const val VIDEOS_ONLY = "EgIQAQ%3D%3D"

    private const val ROW_PREFIX = "Playlist - "
    private const val MIN_ROW_HEIGHT = 100
    private const val MAX_ROW_HEIGHT = 400
    private val COUNT = Regex("""^\d[\d,.]*\s+videos?$""", RegexOption.IGNORE_CASE)

    /** What YouTube keeps for every account, which "play my playlists" without a name should not pick. */
    private val SYSTEM_PLAYLISTS = setOf("liked videos", "watch later", "history", "your videos")

    fun searchUrl(query: String): String =
        "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}&sp=$VIDEOS_ONLY"

    fun linkFor(source: PlaySource): String? = when (source) {
        PlaySource.FAVORITES -> LIKED_LINK
        PlaySource.MY_PLAYLISTS -> PLAYLISTS_LINK
        PlaySource.SEARCH -> null
    }

    /** The playlists listed on the page, top to bottom; [LibraryRow.marked] is true for the ones every account has. */
    fun rows(nodes: List<ScreenNode>, packageName: String): List<LibraryRow> =
        nodes
            .filter { it.packageName == packageName && it.clickable && it.bottom - it.top in MIN_ROW_HEIGHT..MAX_ROW_HEIGHT }
            .filter { it.description.startsWith(ROW_PREFIX) }
            .sortedBy { it.top }
            .map { box ->
                val title = titleOf(box.description.removePrefix(ROW_PREFIX))
                LibraryRow(title, TextMatch.normalize(title) in SYSTEM_PLAYLISTS, box.left, box.top, box.right, box.bottom)
            }

    /** Where to press for [name] (or the first of the user's own playlists), or null when there is none such. */
    fun target(nodes: List<ScreenNode>, packageName: String, name: String): TapPoint? {
        val row = SpotifyLibrary.pick(rows(nodes, packageName), name) ?: return null
        // The left part of the row, away from the action menu at its right edge.
        return TapPoint(row.left + (row.right - row.left) / 4, (row.top + row.bottom) / 2, row.title)
    }

    /**
     * The title out of "<title> - <owner> - <n> videos[ - Updated ...]": everything before the owner, so a title
     * with a dash in it stays whole. Without the count (another language) it is what comes before the first dash.
     */
    internal fun titleOf(description: String): String {
        val parts = description.split(" - ")
        val count = parts.indexOfFirst { COUNT.matches(it.trim()) }
        return if (count >= 2) parts.take(count - 1).joinToString(" - ").trim() else parts.first().trim()
    }
}

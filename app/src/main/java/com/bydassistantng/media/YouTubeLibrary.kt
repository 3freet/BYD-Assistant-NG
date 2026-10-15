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

    /** A video the results page lists: its id and title (the title is empty when the page does not give one). */
    data class VideoHit(val id: String, val title: String)

    fun watchUrl(id: String): String = "https://www.youtube.com/watch?v=$id"

    private val WATCH_LINK = Regex("""^https?://(?:www\.|m\.)?(?:youtube\.com/watch\?(?:[^\s#]*&)?v=|youtu\.be/)([A-Za-z0-9_-]{11})""")

    /** The id of the video a pasted YouTube link names, or null when [text] is not one. */
    fun videoIdOf(text: String): String? = WATCH_LINK.find(text.trim())?.groupValues?.get(1)

    private val HEX_ESCAPE = Regex("""\\x([0-9a-fA-F]{2})""")
    private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
    private val VIDEO_RENDERER = Regex(""""videoRenderer":\{"videoId":"([A-Za-z0-9_-]{11})"""")
    private val WATCH_ENDPOINT = Regex(""""watchEndpoint":\{"videoId":"([A-Za-z0-9_-]{11})"""")
    private val RUN_TEXT = Regex(""""(?:title|headline)":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"""")
    private const val TITLE_WINDOW = 3000

    /**
     * The first video in the page of search results: the id of the first video result and, when the page gives it,
     * its title. The page carries its data as JSON, in the mobile form as a string with `\x22`-style escapes, which
     * are undone first. Null when the page lists no video (the layout changed, or a consent page came instead).
     */
    fun firstVideo(page: String): VideoHit? {
        val text = if ("\\x22" in page) HEX_ESCAPE.replace(page) { it.groupValues[1].toInt(16).toChar().toString() } else page
        val hit = VIDEO_RENDERER.find(text) ?: WATCH_ENDPOINT.find(text) ?: return null
        val from = hit.range.last + 1
        val title = RUN_TEXT.find(text.substring(from, minOf(text.length, from + TITLE_WINDOW)))
            ?.groupValues?.get(1)?.let { unescapeJson(it) }.orEmpty()
        return VideoHit(hit.groupValues[1], title)
    }

    private fun unescapeJson(text: String): String =
        UNICODE_ESCAPE.replace(text) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")

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

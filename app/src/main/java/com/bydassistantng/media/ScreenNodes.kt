package com.bydassistantng.media

import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** One element of the screen as `uiautomator dump` reports it. */
data class ScreenNode(
    val packageName: String,
    val resourceId: String,
    val text: String,
    val description: String,
    val clickable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(other: ScreenNode): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
}

/** Where to press, and what is there (for the log and the answer to the user). */
data class TapPoint(val x: Int, val y: Int, val label: String)

object ScreenDump {
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    /** The elements of a dump in document order; empty when [xml] is not a dump. */
    fun parse(xml: String): List<ScreenNode> {
        val start = xml.indexOf("<?xml").takeIf { it >= 0 } ?: xml.indexOf("<hierarchy").takeIf { it >= 0 } ?: return emptyList()
        return try {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml.substring(start))))
            val elements = document.getElementsByTagName("node")
            (0 until elements.length).mapNotNull { index ->
                val element = elements.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
                val bounds = BOUNDS.matchEntire(element.getAttribute("bounds").trim()) ?: return@mapNotNull null
                val (left, top, right, bottom) = bounds.destructured
                ScreenNode(
                    packageName = element.getAttribute("package"),
                    resourceId = element.getAttribute("resource-id"),
                    text = element.getAttribute("text"),
                    description = element.getAttribute("content-desc"),
                    clickable = element.getAttribute("clickable") == "true",
                    left = left.toInt(),
                    top = top.toInt(),
                    right = right.toInt(),
                    bottom = bottom.toInt(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Which element to press to start the best match once an app has shown its search results. Both apps stop on
 * the results when asked to play from a search; pressing the top result is what a person does next. The rules
 * rely on the identifiers the apps publish for accessibility, so they are the part to revisit when an app
 * changes its screens.
 */
object FirstResult {
    const val SPOTIFY = "com.spotify.music"
    const val YOUTUBE = "com.google.android.youtube"

    private const val SPOTIFY_ROW = ":id/row_root"
    private const val SPOTIFY_SONG_MENU = "More options for song"

    /** Null when the package has no rule or its results are not on screen (yet). */
    fun forPackage(packageName: String, nodes: List<ScreenNode>, kind: MediaKind): TapPoint? = when (packageName) {
        SPOTIFY -> spotify(nodes, kind)
        YOUTUBE -> youtube(nodes, kind)
        else -> null
    }

    fun hasRule(packageName: String): Boolean = packageName == SPOTIFY || packageName == YOUTUBE

    /**
     * Whether finding the result needs the system's UI automation, which switches the accessibility services
     * off while it looks (so it must wait until no conversation is running). Spotify's rows are found from the
     * app's own view dump instead.
     */
    fun needsUiAutomation(packageName: String): Boolean = packageName == YOUTUBE

    /**
     * Spotify from [ViewHierarchy] nodes. For a song, the first row that is a song (its "more options" button says
     * so). For anything else, the first entry of the results list, which is a differently drawn top-result card
     * when the best match is a playlist, album or artist (it has no identifier, so it is taken by position).
     */
    fun spotifyFromViews(nodes: List<ViewNode>, kind: MediaKind): TapPoint? {
        val children = spotifyResultEntries(nodes)
        val menus = nodes.filter { it.activityPackage == SPOTIFY && it.resourceId.endsWith("id/context_menu_button") && it.width > 0 && it.height > 0 }
        val chosen = if (kind == MediaKind.SONG) children.firstOrNull { row -> menus.any { row.contains(it) } } else children.firstOrNull()
        chosen ?: return null
        return TapPoint(chosen.left + chosen.width / 4, chosen.centerY, "")
    }

    /** The entries of Spotify's search results list, top to bottom (empty when the list is not on screen). */
    fun spotifyResultEntries(nodes: List<ViewNode>): List<ViewNode> {
        val listIndex = nodes.indices.firstOrNull {
            nodes[it].activityPackage == SPOTIFY && nodes[it].resourceId.endsWith("id/search_content_recyclerview")
        } ?: return emptyList()
        val list = nodes[listIndex]
        // The list's direct children, in order: the deeper level that follows it, until the nesting comes back out.
        val children = mutableListOf<ViewNode>()
        var childIndent = -1
        for (i in listIndex + 1 until nodes.size) {
            val node = nodes[i]
            if (node.indent <= list.indent) break
            if (childIndent < 0) childIndent = node.indent
            if (node.indent == childIndent && node.height > 0 && node.width > 0) children.add(node)
        }
        return children
    }

    /** How many entries make a results list worth pressing: a list that is still filling in has fewer. */
    const val SETTLED_ENTRIES = 4

    /**
     * Whether Spotify is showing a playlist, album or artist page rather than the search results: the page has its
     * own root view and the results list is gone from the hierarchy.
     */
    fun spotifyEntityPageOpen(nodes: List<ViewNode>): Boolean {
        val mine = nodes.filter { it.activityPackage == SPOTIFY }
        return mine.any { it.resourceId.endsWith("id/list_entity_root") } && mine.none { it.resourceId.endsWith("id/search_content_recyclerview") }
    }

    private val PLAY_BUTTON = Regex("""(?i)^play( playlist| album| artist| podcast| show)?$""")

    /**
     * The green play control of a playlist, album or artist page, found by its accessibility label (those pages
     * are drawn without view identifiers).
     */
    fun playButtonOnPage(nodes: List<ScreenNode>, packageName: String): TapPoint? {
        val button = nodes.firstOrNull { it.packageName == packageName && PLAY_BUTTON.matches(it.description.trim()) } ?: return null
        return TapPoint(button.centerX, button.centerY, button.description)
    }

    /**
     * The first result row. For a song, the first row that is a song (its menu button says so), so a top result
     * that is an artist or podcast is skipped. The press goes on the left part of the row, away from the buttons
     * at its right edge.
     */
    private fun spotify(nodes: List<ScreenNode>, kind: MediaKind): TapPoint? {
        val mine = nodes.filter { it.packageName == SPOTIFY }
        val rows = mine.filter { it.clickable && it.resourceId.endsWith(SPOTIFY_ROW) }
        val songMenus = mine.filter { it.description.startsWith(SPOTIFY_SONG_MENU) }
        val chosen = if (kind == MediaKind.SONG) rows.firstOrNull { row -> songMenus.any { row.contains(it) } } else rows.firstOrNull()
        chosen ?: return null
        val label = songMenus.firstOrNull { chosen.contains(it) }?.description?.removePrefix("$SPOTIFY_SONG_MENU ").orEmpty()
        return TapPoint(chosen.left + (chosen.right - chosen.left) / 4, chosen.centerY, label)
    }

    /**
     * A regular video if there is one ("... - play video"), otherwise a Short ("... - play Short"). Each result
     * item describes itself that way for accessibility.
     */
    private fun youtube(nodes: List<ScreenNode>, kind: MediaKind): TapPoint? {
        val items = nodes.filter { it.packageName == YOUTUBE && it.clickable }
        val video = items.firstOrNull { it.description.endsWith("play video", ignoreCase = true) }
        val short = items.firstOrNull { it.description.endsWith("play Short", ignoreCase = true) }
        val chosen = video ?: short ?: return null
        return TapPoint(chosen.centerX, chosen.centerY, chosen.description.substringBefore(',').take(80))
    }
}

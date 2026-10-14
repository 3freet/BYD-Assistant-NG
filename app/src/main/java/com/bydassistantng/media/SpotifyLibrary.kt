package com.bydassistantng.media

/** One row of the library list as the accessibility tree shows it: the title, and the box to press. */
data class LibraryRow(
    val title: String,
    /** The row carries an icon with a label of its own (pinned, downloaded): Liked Songs is pinned by default. */
    val marked: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * "Play my favorites" and "play music from my playlists" in Spotify. Both are pages of the app with links of
 * their own, so nothing is searched: the link opens the page, and the play control of what opens is pressed.
 *
 * - `spotify:collection:tracks` is the Liked Songs page, whose big play control is the one to press.
 * - `spotify:collection:playlists` is Your Library with the playlists filter on, most recent first, with Liked
 *   Songs pinned at the top. A row of it opens a playlist's page, whose play control is pressed in turn.
 *
 * The pages are drawn without view identifiers, so rows are read from the accessibility tree (see
 * [UiPlayStarter]) and picked by their title.
 */
object SpotifyLibrary {
    const val FAVORITES_LINK = "spotify:collection:tracks"
    const val PLAYLISTS_LINK = "spotify:collection:playlists"

    private const val MIN_ROW_HEIGHT = 48
    private const val MAX_ROW_HEIGHT = 400

    /** The link that opens [source]'s page, or null when [packageName] has none (only Spotify does). */
    fun linkFor(packageName: String, source: PlaySource): String? {
        if (packageName != FirstResult.SPOTIFY) return null
        return when (source) {
            PlaySource.FAVORITES -> FAVORITES_LINK
            PlaySource.MY_PLAYLISTS -> PLAYLISTS_LINK
            PlaySource.SEARCH -> null
        }
    }

    /**
     * The rows of the list, top to bottom. They are the clickable boxes that hold a title and share their left and
     * right edges; a sort control or a heading, which are clickable and hold text too, do not line up with them.
     */
    fun rows(nodes: List<ScreenNode>, packageName: String): List<LibraryRow> {
        val mine = nodes.filter { it.packageName == packageName && it.right > it.left && it.bottom > it.top }
        val texts = mine.filter { it.text.isNotBlank() }
        val candidates = mine
            .filter { it.clickable && it.bottom - it.top in MIN_ROW_HEIGHT..MAX_ROW_HEIGHT }
            .mapNotNull { box ->
                val inside = texts.filter { box.contains(it) }.sortedWith(compareBy({ it.top }, { it.left }))
                if (inside.isEmpty()) null else box to inside
            }
        val list = candidates
            .groupBy { it.first.left to it.first.right }
            .values
            .maxWithOrNull(compareBy({ it.size }, { it.first().first.right - it.first().first.left }))
            ?: return emptyList()
        return list.sortedBy { it.first.top }.map { (box, inside) ->
            val marked = mine.any { other ->
                other !== box && other.text.isBlank() && other.description.isNotBlank() && box.contains(other) &&
                    !(other.left == box.left && other.top == box.top && other.right == box.right && other.bottom == box.bottom)
            }
            LibraryRow(inside.first().text.trim(), marked, box.left, box.top, box.right, box.bottom)
        }
    }

    /**
     * The row to open. With a [name], the row whose title fits it best (nothing when none does); without one, the
     * most recent playlist that is not pinned, which skips Liked Songs (that is "favorites", not a playlist).
     */
    fun pick(rows: List<LibraryRow>, name: String): LibraryRow? {
        if (rows.isEmpty()) return null
        val wanted = TextMatch.normalize(name)
        if (wanted.isEmpty()) return rows.firstOrNull { !it.marked } ?: rows.first()
        return rows
            .map { it to TextMatch.score(TextMatch.normalize(it.title), wanted) }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy({ it.second }, { -it.first.top }))
            ?.first
    }

    /** Where to press for [name] (or the most recent playlist), or null when the list or the name is not there. */
    fun target(nodes: List<ScreenNode>, packageName: String, name: String): TapPoint? {
        val row = pick(rows(nodes, packageName), name) ?: return null
        // The left part of the row, away from the buttons at its right edge.
        return TapPoint(row.left + (row.right - row.left) / 4, (row.top + row.bottom) / 2, row.title)
    }
}

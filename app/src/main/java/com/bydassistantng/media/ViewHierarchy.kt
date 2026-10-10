package com.bydassistantng.media

/** One view of an app's window, with its position on the screen. */
data class ViewNode(
    val activityPackage: String,
    val resourceId: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerY: Int get() = (top + bottom) / 2

    fun contains(other: ViewNode): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
}

/**
 * Reads the view tree out of `dumpsys activity top`, which every app answers for its own windows.
 *
 * Unlike the system's UI automation this does not switch accessibility services off and does not wait for the
 * screen to be idle (a playing track keeps it busy for ever). It carries identifiers and positions but no text,
 * which is enough for apps that give their rows stable ids. Each view is printed with bounds relative to its parent,
 * so the position on screen is the sum along the chain of ancestors.
 */
object ViewHierarchy {
    private val ACTIVITY = Regex("""^\s*ACTIVITY (\S+?)/""")
    private val VIEW = Regex("""^(\s*)\S+\{[0-9a-f]+ \S+ \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)(?: #[0-9a-f]+)?(?: (\S+))?\}""")

    fun parse(dump: String): List<ViewNode> = try {
        parseLines(dump)
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseLines(dump: String): List<ViewNode> {
        val nodes = mutableListOf<ViewNode>()
        var activity = ""
        val ancestors = ArrayList<Triple<Int, Int, Int>>() // indent, left, top
        for (line in dump.lineSequence()) {
            ACTIVITY.find(line)?.let { activity = it.groupValues[1] }
            if (line.contains("View Hierarchy:")) {
                ancestors.clear()
                continue
            }
            val view = VIEW.find(line) ?: continue
            val indent = view.groupValues[1].length
            val left = view.groupValues[2].toInt()
            val top = view.groupValues[3].toInt()
            val right = view.groupValues[4].toInt()
            val bottom = view.groupValues[5].toInt()
            while (ancestors.isNotEmpty() && ancestors.last().first >= indent) ancestors.removeAt(ancestors.size - 1)
            val offsetX = ancestors.sumOf { it.second }
            val offsetY = ancestors.sumOf { it.third }
            ancestors.add(Triple(indent, left, top))
            nodes.add(ViewNode(activity, view.groupValues[6], offsetX + left, offsetY + top, offsetX + right, offsetY + bottom))
        }
        return nodes
    }
}

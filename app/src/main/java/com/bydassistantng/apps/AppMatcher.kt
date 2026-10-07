package com.bydassistantng.apps

data class InstalledApp(val label: String, val packageName: String)

sealed interface AppMatch {
    data class One(val app: InstalledApp) : AppMatch
    /** Several apps fit equally well: the user (via the model) has to say which. */
    data class Several(val apps: List<InstalledApp>) : AppMatch
    data object None : AppMatch
}

/**
 * Finds the installed app a spoken name refers to. The name comes from the model, which has already turned
 * what was said (in Arabic or English) into its best guess at the app's label, so this is forgiving about
 * case, diacritics, Arabic letter variants and extra words ("the camera app") but not about guesswork.
 */
object AppMatcher {
    fun normalize(text: String): String = buildString {
        for (c in text.lowercase()) {
            when {
                c in '\u064B'..'\u065F' || c == '\u0640' || c == '\u0670' -> Unit // diacritics, tatweel
                c == '\u0623' || c == '\u0625' || c == '\u0622' -> append('\u0627') // alef variants -> alef
                c == '\u0649' -> append('\u064A') // alef maksura -> yeh
                c == '\u0629' -> append('\u0647') // teh marbuta -> heh
                c.isLetterOrDigit() -> append(c)
                else -> append(' ')
            }
        }
    }.trim().replace(Regex("\\s+"), " ")

    private val FILLER = setOf("the", "app", "application", "open", "launch", "start", "تطبيق", "برنامج", "افتح", "شغل")

    private fun words(normalized: String): List<String> = normalized.split(' ').filter { it.isNotEmpty() && it !in FILLER }

    fun find(query: String, apps: List<InstalledApp>): AppMatch {
        val q = normalize(query)
        val queryWords = words(q)
        if (queryWords.isEmpty()) return AppMatch.None
        val core = queryWords.joinToString(" ")

        val scored = apps.mapNotNull { app ->
            val label = normalize(app.label)
            val labelWords = words(label)
            val labelCore = labelWords.joinToString(" ")
            val score = when {
                label == q || labelCore == core -> 100
                labelCore.startsWith(core) && core.length >= 3 -> 80
                labelWords.containsAll(queryWords) -> 70
                queryWords.containsAll(labelWords) && labelWords.isNotEmpty() -> 60
                else -> {
                    val shared = queryWords.count { it in labelWords }
                    when {
                        shared > 0 && shared * 2 >= queryWords.size -> 40
                        // The package name often carries the brand when the label is localized ("spotify").
                        queryWords.any { it.length >= 4 && app.packageName.lowercase().contains(it) } -> 30
                        else -> 0
                    }
                }
            }
            if (score > 0) app to score else null
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) return AppMatch.None
        val best = scored.first().second
        val top = scored.filter { it.second == best }.map { it.first }
        return when {
            best >= 100 -> AppMatch.One(top.first())
            top.size == 1 -> AppMatch.One(top.first())
            else -> AppMatch.Several(top.take(5))
        }
    }
}

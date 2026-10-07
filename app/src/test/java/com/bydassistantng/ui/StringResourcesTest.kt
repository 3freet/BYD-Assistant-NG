package com.bydassistantng.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the translations: the Arabic file must have exactly the English file's strings, with the same
 * format placeholders in each (a missing or mismatched one is a crash or a blank in the other language),
 * and nothing left in English by accident.
 */
class StringResourcesTest {
    private val resDir = File("src/main/res")
    private val entry = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%\d\$[sd]""")

    /** The strings of one folder; brand names marked `translatable="false"` are left out of the comparison. */
    private fun strings(folder: String): Map<String, String> =
        entry.findAll(File(resDir, "$folder/strings.xml").readText())
            .filter { !it.groupValues[2].contains("translatable=\"false\"") }
            .associate { it.groupValues[1] to it.groupValues[3] }

    private val english get() = strings("values")
    private val arabic get() = strings("values-ar")

    @Test
    fun arabicHasExactlyTheEnglishStrings() {
        assertEquals("keys missing from values-ar", emptySet<String>(), english.keys - arabic.keys)
        assertEquals("keys in values-ar that English lacks", emptySet<String>(), arabic.keys - english.keys)
    }

    @Test
    fun placeholdersMatchInBothLanguages() {
        for ((name, en) in english) {
            val ar = arabic.getValue(name)
            assertEquals("placeholders of $name", placeholder.findAll(en).map { it.value }.sorted().toList(), placeholder.findAll(ar).map { it.value }.sorted().toList())
        }
    }

    @Test
    fun arabicStringsAreActuallyArabic() {
        // Names and technical terms stay Latin, but a sentence with no Arabic letter at all is a missed translation.
        val keepLatin = setOf("app_language_english")
        for ((name, ar) in arabic) {
            if (name in keepLatin) continue
            assertTrue("$name has no Arabic letters: $ar", ar.any { it in '\u0600'..'\u06FF' })
        }
    }

    @Test
    fun appLanguageNamesAreShownInTheirOwnLanguageInBothFiles() {
        for (strings in listOf(english, arabic)) {
            assertEquals("English", strings.getValue("app_language_english"))
            assertEquals("العربية", strings.getValue("app_language_arabic"))
        }
    }

    @Test
    fun theAppNameIsABrandAndStaysLatinInEveryLanguage() {
        val main = File(resDir, "values/strings.xml").readText()
        assertTrue("app_name must be translatable=\"false\"", Regex("""<string name="app_name"[^>]*translatable="false"""").containsMatchIn(main))
        assertTrue("Arabic must not override app_name", !File(resDir, "values-ar/strings.xml").readText().contains("name=\"app_name\""))
        // ...and Arabic sentences that mention the app use the same Latin name.
        assertTrue(arabic.values.none { "مساعد BYD" in it })
    }
}

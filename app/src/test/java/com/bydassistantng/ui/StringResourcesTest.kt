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
    private val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%\d\$[sd]""")

    private fun strings(folder: String): Map<String, String> =
        entry.findAll(File(resDir, "$folder/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }

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
}

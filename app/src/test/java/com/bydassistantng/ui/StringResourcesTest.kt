package com.bydassistantng.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the translations: the Arabic and Russian files must have exactly the English file's strings, with the
 * same format placeholders in each (a missing or mismatched one is a crash or a blank in the other language),
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
    private val russian get() = strings("values-ru")
    private val polish get() = strings("values-pl")
    // values-in, not values-id: Android still reports Indonesian with its legacy code "in".
    private val indonesian get() = strings("values-in")

    @Test
    fun arabicHasExactlyTheEnglishStrings() {
        assertEquals("keys missing from values-ar", emptySet<String>(), english.keys - arabic.keys)
        assertEquals("keys in values-ar that English lacks", emptySet<String>(), arabic.keys - english.keys)
    }

    @Test
    fun russianHasExactlyTheEnglishStrings() {
        assertEquals("keys missing from values-ru", emptySet<String>(), english.keys - russian.keys)
        assertEquals("keys in values-ru that English lacks", emptySet<String>(), russian.keys - english.keys)
    }

    @Test
    fun polishHasExactlyTheEnglishStrings() {
        assertEquals("keys missing from values-pl", emptySet<String>(), english.keys - polish.keys)
        assertEquals("keys in values-pl that English lacks", emptySet<String>(), polish.keys - english.keys)
    }

    @Test
    fun indonesianHasExactlyTheEnglishStrings() {
        assertEquals("keys missing from values-in", emptySet<String>(), english.keys - indonesian.keys)
        assertEquals("keys in values-in that English lacks", emptySet<String>(), indonesian.keys - english.keys)
    }

    @Test
    fun indonesianStringsAreNotLeftInEnglish() {
        // Indonesian is written in the Latin script like English, so a missed translation is told by being the English
        // text. These are names, a bare unit, or a word the two share ("Volume", "Media", "Beta").
        val sameAsEnglish = setOf(
            "app_language_english", "app_language_arabic", "app_language_russian", "app_language_polish",
            "app_language_indonesian", "channel_beta", "banner_media", "group_volume", "end_wait_value",
            "sensitivity_value", "voice_channel_dip_value", "voice_channel_gain_value",
        )
        for ((name, id) in indonesian) {
            if (name in sameAsEnglish) continue
            assertTrue("$name is still English: $id", id != english.getValue(name))
        }
    }

    @Test
    fun polishStringsAreNotLeftInEnglish() {
        // Polish is written in the Latin script like English, so a missed translation cannot be told by its letters:
        // it is told by being the English text. These are names, a bare unit or a word the two share.
        val sameAsEnglish = setOf(
            "app_language_english", "app_language_arabic", "app_language_russian", "app_language_polish",
            "app_language_indonesian", "language_auto", "channel_beta", "media_stop", "end_wait_value", "sensitivity_value",
            "voice_channel_dip_value", "voice_channel_gain_value",
        )
        for ((name, pl) in polish) {
            if (name in sameAsEnglish) continue
            assertTrue("$name is still English: $pl", pl != english.getValue(name))
        }
    }

    @Test
    fun placeholdersMatchInEveryLanguage() {
        for ((name, en) in english) {
            for ((language, translated) in listOf("Arabic" to arabic, "Russian" to russian, "Polish" to polish, "Indonesian" to indonesian)) {
                assertEquals(
                    "$language placeholders of $name",
                    placeholder.findAll(en).map { it.value }.sorted().toList(),
                    placeholder.findAll(translated.getValue(name)).map { it.value }.sorted().toList(),
                )
            }
        }
    }

    @Test
    fun arabicStringsAreActuallyArabic() {
        // Names and technical terms stay Latin, but a sentence with no Arabic letter at all is a missed translation.
        val keepLatin = setOf("app_language_english", "app_language_russian", "app_language_polish", "app_language_indonesian")
        for ((name, ar) in arabic) {
            if (name in keepLatin) continue
            assertTrue("$name has no Arabic letters: $ar", ar.any { it in '\u0600'..'\u06FF' })
        }
    }

    @Test
    fun russianStringsAreActuallyRussian() {
        // Names and technical terms stay Latin, and a bare number has no letters to translate; any other sentence
        // with no Cyrillic letter at all is a missed translation.
        val keepLatin = setOf("app_language_english", "app_language_arabic", "app_language_polish", "app_language_indonesian", "sensitivity_value", "voice_channel_dip_value", "voice_channel_gain_value")
        for ((name, ru) in russian) {
            if (name in keepLatin) continue
            assertTrue("$name has no Cyrillic letters: $ru", ru.any { it in '\u0400'..'\u04FF' })
        }
    }

    @Test
    fun appLanguageNamesAreShownInTheirOwnLanguageInEveryFile() {
        for (strings in listOf(english, arabic, russian, polish, indonesian)) {
            assertEquals("English", strings.getValue("app_language_english"))
            assertEquals("العربية", strings.getValue("app_language_arabic"))
            assertEquals("Русский", strings.getValue("app_language_russian"))
            assertEquals("Polski", strings.getValue("app_language_polish"))
            assertEquals("Bahasa Indonesia", strings.getValue("app_language_indonesian"))
        }
    }

    @Test
    fun theAppNameIsABrandAndStaysLatinInEveryLanguage() {
        val main = File(resDir, "values/strings.xml").readText()
        assertTrue("app_name must be translatable=\"false\"", Regex("""<string name="app_name"[^>]*translatable="false"""").containsMatchIn(main))
        assertTrue("Arabic must not override app_name", !File(resDir, "values-ar/strings.xml").readText().contains("name=\"app_name\""))
        assertTrue("Russian must not override app_name", !File(resDir, "values-ru/strings.xml").readText().contains("name=\"app_name\""))
        assertTrue("Indonesian must not override app_name", !File(resDir, "values-in/strings.xml").readText().contains("name=\"app_name\""))
        assertTrue("Polish must not override app_name", !File(resDir, "values-pl/strings.xml").readText().contains("name=\"app_name\""))
        // ...and Arabic sentences that mention the app use the same Latin name.
        assertTrue(arabic.values.none { "مساعد BYD" in it })
        assertTrue(russian.values.none { "Ассистент BYD" in it })
        assertTrue(polish.values.none { "Asystent BYD Assistant" in it })
        assertTrue(indonesian.values.none { "Asisten BYD Assistant" in it })
    }
}

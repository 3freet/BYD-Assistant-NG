package com.bydassistantng.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCategoryTest {
    @Test
    fun theSearchFindsAnyPartOfAnyNameInAnyCase() {
        assertTrue(matchesQuery("vol", "Volume follows the cabin noise"))
        assertTrue(matchesQuery("CABIN", "Volume follows the cabin noise"))
        assertTrue(matchesQuery("  noise ", "Volume follows the cabin noise"))
        assertTrue(matchesQuery("مفتاح", "مفتاح Gemini API"))
    }

    @Test
    fun anEmptyOrUnmatchedQueryFindsNothing() {
        assertFalse(matchesQuery("", "Voice"))
        assertFalse(matchesQuery("   ", "Voice"))
        assertFalse(matchesQuery("memory", "Voice"))
    }

    @Test
    fun everyGroupHasItsOwnNameAndLine() {
        val groups = SettingsCategory.entries
        assertEquals(groups.size, groups.map { it.title }.toSet().size)
        assertEquals(groups.size, groups.map { it.summary }.toSet().size)
        assertEquals(6, groups.size)
    }
}

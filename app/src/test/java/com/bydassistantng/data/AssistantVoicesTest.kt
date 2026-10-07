package com.bydassistantng.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantVoicesTest {
    @Test
    fun namesAreUnique() {
        assertEquals(AssistantVoices.all.size, AssistantVoices.all.map { it.name }.toSet().size)
    }

    @Test
    fun onlyAKnownNameReachesTheApi() {
        assertEquals("Kore", AssistantVoices.valid("Kore"))
        assertNull(AssistantVoices.valid(""))
        assertNull(AssistantVoices.valid("kore")) // the API's names are case-sensitive
        assertNull(AssistantVoices.valid("NotAVoice"))
    }
}

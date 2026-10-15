package com.bydassistantng.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CarVoiceAssistantTest {
    @Test
    fun theAssistantIsSwitchedOffAndOnForTheMainUserOnly() {
        assertEquals("pm disable-user --user 0 com.byd.autovoice", CarVoiceAssistant.command(on = false))
        assertEquals("pm enable --user 0 com.byd.autovoice", CarVoiceAssistant.command(on = true))
    }
}

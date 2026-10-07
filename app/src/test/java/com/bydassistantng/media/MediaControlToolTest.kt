package com.bydassistantng.media

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaControlToolTest {
    @Test
    fun everyActionMapsToItsMediaKey() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, MediaControlTool.keyCodes["play"])
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, MediaControlTool.keyCodes["pause"])
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, MediaControlTool.keyCodes["next"])
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaControlTool.keyCodes["previous"])
        assertEquals(KeyEvent.KEYCODE_MEDIA_STOP, MediaControlTool.keyCodes["stop"])
    }

    @Test
    fun theDeclarationOffersExactlyTheseActions() {
        val actions = MediaControlTool.declaration.parameters!!.properties!!.getValue("action").enum
        assertEquals(MediaControlTool.keyCodes.keys.toList(), actions)
    }
}

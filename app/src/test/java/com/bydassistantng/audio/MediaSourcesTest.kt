package com.bydassistantng.audio

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSourcesTest {
    private val ignored = setOf("com.bydassistantng", "com.android.server.telecom")

    @Test
    fun anAppInAPlayingStateIsPlaying() {
        val sessions = listOf("com.byd.mediacenter" to PlaybackState.STATE_PLAYING, "com.spotify.music" to PlaybackState.STATE_PAUSED)
        assertEquals(setOf("com.byd.mediacenter"), MediaSources.playingPackages(sessions, ignored))
    }

    @Test
    fun bufferingCountsAsAboutToPlayButPausedStoppedAndErrorDoNot() {
        val sessions = listOf(
            "a" to PlaybackState.STATE_BUFFERING,
            "b" to PlaybackState.STATE_PAUSED,
            "c" to PlaybackState.STATE_STOPPED,
            "d" to PlaybackState.STATE_ERROR,
            "e" to PlaybackState.STATE_NONE,
            "f" to null,
        )
        assertEquals(setOf("a"), MediaSources.playingPackages(sessions, ignored))
    }

    @Test
    fun theAssistantAndThePhoneAreNotMusic() {
        val sessions = listOf("com.bydassistantng" to PlaybackState.STATE_PLAYING, "com.android.server.telecom" to PlaybackState.STATE_PLAYING)
        assertEquals(emptySet<String>(), MediaSources.playingPackages(sessions, ignored))
    }

    @Test
    fun severalPlayingAppsAreAllListed() {
        val sessions = listOf("x" to PlaybackState.STATE_PLAYING, "y" to PlaybackState.STATE_PLAYING)
        assertEquals(setOf("x", "y"), MediaSources.playingPackages(sessions, ignored))
    }
}

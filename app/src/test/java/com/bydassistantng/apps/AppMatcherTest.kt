package com.bydassistantng.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {
    private val apps = listOf(
        InstalledApp("YouTube", "com.google.android.youtube"),
        InstalledApp("YouTube Music", "com.google.android.apps.youtube.music"),
        InstalledApp("Google Maps", "com.google.android.apps.maps"),
        InstalledApp("Camera", "com.byd.camera"),
        InstalledApp("Spotify", "com.spotify.music"),
        InstalledApp("الراديو", "com.byd.radio"),
        InstalledApp("Settings", "com.android.settings"),
    )

    private fun label(match: AppMatch) = (match as? AppMatch.One)?.app?.label

    @Test
    fun anExactNameWins_evenWhenOthersContainIt() {
        assertEquals("YouTube", label(AppMatcher.find("youtube", apps)))
        assertEquals("YouTube Music", label(AppMatcher.find("YouTube Music", apps)))
    }

    @Test
    fun fillerWordsAreIgnored() {
        assertEquals("Camera", label(AppMatcher.find("the camera app", apps)))
        assertEquals("Google Maps", label(AppMatcher.find("open maps", apps)))
    }

    @Test
    fun arabicNamesMatchDespiteDiacriticsAndLetterVariants() {
        assertEquals("الراديو", label(AppMatcher.find("الراديو", apps)))
        assertEquals("الراديو", label(AppMatcher.find("الرَادِيُو", apps)))
    }

    @Test
    fun russianNamesMatchWhetherOrNotYoIsWritten() {
        val russian = listOf(InstalledApp("Яндекс Навигатор", "ru.yandex.yandexnavi"), InstalledApp("Календарь", "com.byd.calendar"), InstalledApp("Всё о машине", "com.byd.info"))
        assertEquals("Яндекс Навигатор", label(AppMatcher.find("яндекс навигатор", russian)))
        assertEquals("Всё о машине", label(AppMatcher.find("все о машине", russian)))
        assertEquals("Всё о машине", label(AppMatcher.find("Всё о машине", russian)))
    }

    @Test
    fun russianFillerWordsAreIgnored() {
        val russian = listOf(InstalledApp("Камера", "com.byd.camera"), InstalledApp("Календарь", "com.byd.calendar"))
        assertEquals("Камера", label(AppMatcher.find("открой приложение камера", russian)))
        assertEquals("Календарь", label(AppMatcher.find("запусти календарь", russian)))
    }

    @Test
    fun thePackageNameCoversALocalizedLabel() {
        val localized = listOf(InstalledApp("سبوتيفاي", "com.spotify.music"), InstalledApp("Camera", "com.byd.camera"))
        assertEquals("سبوتيفاي", label(AppMatcher.find("spotify", localized)))
    }

    @Test
    fun anAmbiguousNameListsTheCandidatesInsteadOfGuessing() {
        val two = listOf(InstalledApp("Video Player", "a.b.c"), InstalledApp("Video Recorder", "d.e.f"))
        val match = AppMatcher.find("video", two)
        assertTrue("expected Several, got $match", match is AppMatch.Several)
        assertEquals(2, (match as AppMatch.Several).apps.size)
    }

    @Test
    fun anUnknownNameMatchesNothing() {
        assertEquals(AppMatch.None, AppMatcher.find("quantum chess", apps))
        assertEquals(AppMatch.None, AppMatcher.find("   ", apps))
        assertEquals(AppMatch.None, AppMatcher.find("open the app", apps)) // only filler words
    }
}

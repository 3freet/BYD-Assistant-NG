package com.bydassistantng.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenBackTest {
    @Test
    fun settingsGoesBackToHomeAndItsPagesBackToSettings() {
        assertEquals(Screen.HOME, Screen.SETTINGS.backTarget(updatesFrom = Screen.SETTINGS))
        assertEquals(Screen.SETTINGS, Screen.ABOUT.backTarget(updatesFrom = Screen.SETTINGS))
    }

    @Test
    fun theLogsGoBackToTheSettings() {
        assertEquals(Screen.SETTINGS, Screen.CRASH_LOG.backTarget(updatesFrom = Screen.SETTINGS))
        assertEquals(Screen.SETTINGS, Screen.APP_LOG.backTarget(updatesFrom = Screen.SETTINGS))
    }

    @Test
    fun theUpdatesPageGoesBackToWhereItWasOpenedFrom() {
        assertEquals(Screen.HOME, Screen.UPDATES.backTarget(updatesFrom = Screen.HOME))
        assertEquals(Screen.SETTINGS, Screen.UPDATES.backTarget(updatesFrom = Screen.SETTINGS))
    }

    @Test
    fun homeAndTheOnboardingHaveNoStepBackSoTheAppLeavesFromThere() {
        assertNull(Screen.HOME.backTarget(updatesFrom = Screen.SETTINGS))
        assertNull(Screen.ONBOARDING.backTarget(updatesFrom = Screen.SETTINGS))
    }
}

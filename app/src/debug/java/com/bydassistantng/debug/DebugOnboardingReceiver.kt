package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.util.AppLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface OnboardingPreferencesEntryPoint {
    fun preferences(): PreferencesRepository
}

/**
 * Shows the first-time setup screen again, to look at it without losing anything: the app starts on it the next time
 * it is opened, and nothing is cleared (the key, the memories and the settings stay). Put it back the same way.
 *
 *     am broadcast -a com.bydassistantng.debug.ONBOARDING -n <pkg>/com.bydassistantng.debug.DebugOnboardingReceiver --ez show true
 *
 * `--ez show false` marks the setup as done again. Only exists in debug builds.
 */
class DebugOnboardingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val show = intent.getBooleanExtra("show", true)
        val preferences = EntryPointAccessors.fromApplication(context.applicationContext, OnboardingPreferencesEntryPoint::class.java).preferences()
        runBlocking { preferences.setOnboardingCompleted(!show) }
        AppLogger.log("DebugOnboarding", "The first-time setup is now ${if (show) "to be shown" else "done"}")
    }
}

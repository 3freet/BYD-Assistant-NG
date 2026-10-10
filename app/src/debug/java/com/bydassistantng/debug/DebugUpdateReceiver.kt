package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.update.UpdateManager
import com.bydassistantng.update.UpdateState
import com.bydassistantng.util.AppLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface UpdateEntryPoint {
    fun updateManager(): UpdateManager
}

/**
 * Exercises the whole update flow from a debug build without publishing anything:
 *
 *     adb reverse tcp:8000 tcp:8000        (serve a releases.json and an APK from the laptop on port 8000)
 *     am broadcast -a com.bydassistantng.debug.UPDATE -n <pkg>/com.bydassistantng.debug.DebugUpdateReceiver --es source http://127.0.0.1:8000/releases.json
 *     ... --ez install true     installs the release the check found
 *     ... --ez status true      logs the current update state
 *
 * Everything is logged as "DebugUpdate: …" in the app log. Only exists in debug builds.
 */
class DebugUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = EntryPointAccessors.fromApplication(context.applicationContext, UpdateEntryPoint::class.java).updateManager()
        intent.getStringExtra("source")?.let { url ->
            AppLogger.log(TAG, "Update source set to $url: ${manager.useTestSource(url)}")
            manager.checkNow()
        }
        if (intent.getBooleanExtra("install", false)) {
            val state = manager.state.value
            if (state is UpdateState.Available) manager.install(state.release) else AppLogger.log(TAG, "Nothing to install, state is $state")
        }
        if (intent.getBooleanExtra("status", false)) AppLogger.log(TAG, "State: ${manager.state.value}")
    }

    private companion object {
        const val TAG = "DebugUpdate"
    }
}

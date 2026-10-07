package com.bydassistantng

import android.app.Application
import android.content.Context
import com.bydassistantng.audio.TextToSpeechEngine
import com.bydassistantng.ota.OtaUpdater
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.util.CrashLogger
import com.bydassistantng.util.WheelKeys
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AssistantApp : Application() {

    @Inject lateinit var otaUpdater: OtaUpdater

    // Just injecting this triggers TextToSpeechEngine's constructor, which starts the (now
    // bounded, but still worth starting early) async engine-init wait during idle app startup
    // instead of mid-conversation on the very first spoken reply.
    @Inject lateinit var ttsEngine: TextToSpeechEngine

    // Everything the app creates from this context (strings, notifications) follows the app language.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Installed first, before anything else can throw during startup.
        CrashLogger.install(this)
        AppLogger.install(this)
        // Whatever started this process (the system binding the service, a boot broadcast, the user opening
        // the app), make sure the wheel button is actually live. Waits a few seconds, so off the main thread.
        Thread { WheelKeys.ensureServiceRunning(this) }.start()
        otaUpdater.checkUpdateInBackground(force = false)
    }
}

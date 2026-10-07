package com.bydassistantng.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.annotation.StringRes
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bydassistantng.audio.AudioRecorder
import com.bydassistantng.audio.Earcon
import com.bydassistantng.audio.Earcons
import com.bydassistantng.audio.TextToSpeechEngine
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.gemini.ConversationController
import com.bydassistantng.gemini.LiveConversationController
import com.bydassistantng.gemini.StartFailure
import com.bydassistantng.gemini.TurnStatus
import com.bydassistantng.ui.EXTRA_REQUEST_MIC_PERMISSION
import com.bydassistantng.ui.MainActivity
import com.bydassistantng.R
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.util.WheelKeys
import com.bydassistantng.util.hasMicPermission
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "WheelKeyService"
private const val RECORDED_AUDIO_MIME_TYPE = "audio/m4a"
private const val CLASSIC_ANSWER_SHOWN_MS = 8_000L
private const val OFFLINE_SHOWN_MS = 8_000L

/**
 * Starts and stops a voice turn from the steering-wheel mic button, and hosts that turn.
 *
 * This is how DiLink button-mapper apps do it: an accessibility service that asks for `flagRequestFilterKeyEvents`, so the system hands it
 * every hardware key before any app sees it. Returning true from [onKeyEvent] consumes the key.
 * Being an accessibility service also means the system binds it by itself at boot and keeps it
 * alive, so there is no foreground service, boot receiver or overlay permission to maintain — and
 * the platform lets accessibility services capture the mic while another app is in front.
 *
 * Which key triggers is a preference (default: the mic button's short press); see [WheelKeys].
 * Anything else listening on the same key (e.g. a button-mapper's own mapping) still fires too —
 * accessibility services are all notified — so that mapping has to be removed there.
 */
@AndroidEntryPoint
class WheelKeyService : AccessibilityService() {

    @Inject lateinit var preferencesRepository: PreferencesRepository
    @Inject lateinit var audioRecorder: AudioRecorder
    @Inject lateinit var conversationController: ConversationController
    @Inject lateinit var liveConversationController: LiveConversationController
    @Inject lateinit var ttsEngine: TextToSpeechEngine
    @Inject lateinit var earcons: Earcons

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    internal val banner by lazy { StatusBanner(this) }
    private var startJob: Job? = null

    @Volatile private var triggerKeyCode = WheelKeys.MIC_SHORT_PRESS

    // The status banner and the messages follow the app language, which can differ from the device's.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    /** A string in the app language as it is now (it may have been changed since this service started). */
    private fun text(@StringRes id: Int, vararg args: Any): String = AppLanguage.string(this, id, *args)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        scope.launch { preferencesRepository.triggerKeyCode.collect { triggerKeyCode = it } }
        AppLogger.log(TAG, "Connected, watching key $triggerKeyCode")
    }

    // Key filtering is all this service is for; it has no use for window/content events.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        keyCaptureListener?.let { listener ->
            // "Press the button you want to use": takes the next key instead of acting on it.
            if (event.action == KeyEvent.ACTION_DOWN) {
                keyCaptureListener = null
                AppLogger.log(TAG, "Captured key ${event.keyCode} for the trigger setting")
                listener(event.keyCode)
            }
            return true
        }

        if (event.keyCode != triggerKeyCode) return false
        // Consumed on both down and up so nothing else reacts to half of the press; only the
        // initial down starts something (holding the button would otherwise auto-repeat).
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            AppLogger.log(TAG, "Trigger key ${event.keyCode} pressed")
            onTriggerPressed()
        }
        return true
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        keyCaptureListener = null
        handler.removeCallbacksAndMessages(null)
        banner.release()
        audioRecorder.cancel()
        liveConversationController.close()
        scope.cancel()
        super.onDestroy()
    }

    // ── Voice turn ───────────────────────────────────────────────────────────────────────────

    /**
     * A press starts a conversation, or ends the one in progress — whatever the assistant is doing at
     * that moment (listening, thinking or speaking). Decided from what is actually running rather than
     * a separately tracked flag, since a conversation can also end on its own (silence, "stop").
     */
    private fun onTriggerPressed() {
        when {
            liveConversationController.inConversation -> liveConversationController.endConversation()
            audioRecorder.isRecording -> {
                val file = audioRecorder.stop()
                if (file == null) signalFailure(text(R.string.err_no_audio)) else runClassicTurn(file)
            }
            // Connecting takes up to a few seconds; a second press in that window must not start a
            // second session on top of the first.
            startJob?.isActive == true -> AppLogger.log(TAG, "Press ignored: a turn is still connecting")
            else -> startNewTurn()
        }
    }

    private fun startNewTurn() {
        // A service can't show a runtime-permission dialog, so if the grant has lapsed (a one-time
        // "Only this time" grant auto-revokes — seen on a head unit) the only thing that can
        // fix it is the app's own screen. Say so and try to open it, rather than failing with a
        // vague "could not start recording".
        if (!hasMicPermission()) {
            signalFailure(text(R.string.err_mic_off))
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(EXTRA_REQUEST_MIC_PERMISSION, true)
                    },
                )
            }.onFailure { AppLogger.logError(TAG, "Could not open the app to request the mic permission", it) }
            return
        }

        // Straight away, so the press is acknowledged before the connection (up to a few seconds) is up.
        banner.show(BannerMode.CONNECTING)
        startJob = scope.launch {
            if (!preferencesRepository.onboardingCompleted.first()) {
                signalFailure(text(R.string.err_finish_setup))
                return@launch
            }

            val liveStarted = liveConversationController.start { status -> handleStatus(status) }
            if (liveStarted) {
                // The Live controller plays the listening tone itself (and the others).
                banner.show(BannerMode.LISTENING)
                return@launch
            }

            // Some failures have an answer the classic pipeline can't improve on — it needs the same
            // internet and key, and would first record 15 seconds of the user talking before saying so.
            val failure = liveConversationController.lastStartFailure
            if (failure != StartFailure.OTHER && failure != StartFailure.NONE) {
                AppLogger.log(TAG, "Live unavailable ($failure) — telling the user instead of falling back")
                signalFailure(liveConversationController.lastStartMessage, offline = failure == StartFailure.NO_NETWORK)
                return@launch
            }

            AppLogger.log(TAG, "Live unavailable this turn, falling back to the classic pipeline")
            val file = audioRecorder.start(onMaxDurationReached = { onTriggerPressed() }, onSilenceDetected = { onTriggerPressed() })
            if (file == null) {
                signalFailure(text(R.string.err_cant_record))
                return@launch
            }
            earcons.play(Earcon.LISTENING)
            banner.show(BannerMode.LISTENING, labelOverride = text(R.string.banner_listening_press_to_stop))
        }
    }

    private fun runClassicTurn(file: File) {
        earcons.play(Earcon.THINKING)
        banner.show(BannerMode.THINKING)
        scope.launch {
            // Speaking happens AFTER runTurn() returns, never from inside its callback: the
            // callback runs while ConversationController's turn mutex is still held, and a hung TTS
            // engine must never be able to block every subsequent voice turn behind it.
            var toSpeak: TurnStatus.Speaking? = null
            conversationController.runTurn(file, RECORDED_AUDIO_MIME_TYPE) { status ->
                handleStatus(status)
                if (status is TurnStatus.Speaking) toSpeak = status
            }
            file.delete()
            // The classic path has no "done" event: leave the answer up long enough to read, then clear it.
            handler.postDelayed({ banner.hide() }, CLASSIC_ANSWER_SHOWN_MS)
            // The classic path only ever gets text back — Live produces real audio output itself,
            // so only this fallback path still needs a local TTS call.
            toSpeak?.let { ttsEngine.speak(it.text, it.language) }
        }
    }

    /** Mirrors what the assistant is doing onto the banner. Called from background threads. */
    private fun handleStatus(status: TurnStatus) {
        when (status) {
            TurnStatus.Listening -> banner.show(BannerMode.LISTENING)
            TurnStatus.Thinking -> banner.show(BannerMode.THINKING)
            is TurnStatus.DispatchingCommand -> banner.show(BannerMode.THINKING, labelOverride = status.displayName)
            is TurnStatus.Speaking -> banner.show(BannerMode.SPEAKING, detail = status.text)
            // The controller already played its error tone for a Live failure.
            is TurnStatus.Failed -> banner.show(BannerMode.ERROR, detail = status.message)
            TurnStatus.Done -> banner.hide()
        }
    }

    // A driver can't be expected to look at the screen, so failures get a tone as well as the banner.
    private fun signalFailure(message: String, offline: Boolean = false) {
        earcons.play(if (offline) Earcon.OFFLINE else Earcon.ERROR)
        // Longer when it says what to do about it: a driver glances at this, and has to read two languages.
        banner.show(BannerMode.ERROR, detail = message, shownMs = if (offline) OFFLINE_SHOWN_MS else null)
    }

    companion object {
        @Volatile var instance: WheelKeyService? = null
            private set

        @Volatile private var keyCaptureListener: ((Int) -> Unit)? = null

        /** Hands [onKey] the next hardware key pressed (and swallows it) instead of triggering a
         * turn — the "press the button you want to use" flow in Settings. Runs on the main thread.
         * Returns false if the service isn't running, in which case no key can be seen at all. */
        fun beginKeyCapture(onKey: (Int) -> Unit): Boolean {
            if (instance == null) return false
            keyCaptureListener = onKey
            return true
        }

        fun cancelKeyCapture() {
            keyCaptureListener = null
        }
    }
}

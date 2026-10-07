package com.bydassistantng.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "TextToSpeechEngine"

// A real device in the field took 23+ minutes for TextToSpeech's OnInitListener to ever fire at
// all (see AudioRecorder/ConversationController app-log timestamps around the "FAILED to
// initialize" line) — almost certainly a broken/missing engine binding hanging at the OS level,
// not this class. Without a bound here, awaitReady() would wait that long (or forever) on every
// speak() call before this class existed to fix it — this timeout is what actually prevents that,
// not just the initResolved/isReady split above.
private const val INIT_TIMEOUT_MS = 5_000L

/**
 * Thin coroutine wrapper over the platform [TextToSpeech] engine — confirmed working without
 * Google Play Services earlier in this project's life, so it's kept as-is rather than chasing a
 * Gemini-native audio-output model for v1.
 */
@Singleton
class TextToSpeechEngine @Inject constructor(@ApplicationContext context: Context) {
    private val lock = Any()

    // Deliberately two separate flags: TextToSpeech's OnInitListener fires exactly once, ever —
    // conflating "ready" with "init has resolved" (as an earlier version of this class did) means
    // that if init ever resolves to failure, awaitReady() queues into `pending` on every later call
    // and hangs forever, since nothing will ever drain that queue again. `initResolved` alone
    // decides whether to wait; `isReady` decides what to do once resolved.
    private var initResolved = false
    private var isReady = false
    private val pending = mutableListOf<() -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        val toRun = synchronized(lock) {
            isReady = status == TextToSpeech.SUCCESS
            initResolved = true
            pending.toList().also { pending.clear() }
        }
        toRun.forEach { it() }

        if (isReady) {
            AppLogger.log(TAG, "TextToSpeech initialized OK (engine=${tts.defaultEngine})")
        } else {
            val engines = runCatching { tts.engines.joinToString { "${it.name} (label=${it.label})" } }.getOrDefault("<query failed>")
            AppLogger.logError(TAG, "TextToSpeech FAILED to initialize (status=$status) — no speech will be produced. Installed engines: $engines")
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                AppLogger.log(TAG, "Utterance $utteranceId started")
            }

            override fun onDone(utteranceId: String?) {
                AppLogger.log(TAG, "Utterance $utteranceId finished")
            }

            @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
            override fun onError(utteranceId: String?) {
                AppLogger.logError(TAG, "Utterance $utteranceId failed")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                AppLogger.logError(TAG, "Utterance $utteranceId failed (errorCode=$errorCode)")
            }
        })
    }

    /** Speaks [text], picking a locale from [language] — for [AssistantLanguage.AUTO], guesses
     * from whether the reply text itself contains Arabic-script characters, since the reply's
     * language is only known once Gemini has already answered. */
    suspend fun speak(text: String, language: AssistantLanguage) {
        val resolvedInTime = withTimeoutOrNull(INIT_TIMEOUT_MS) { awaitReady() } != null
        if (!resolvedInTime) {
            AppLogger.logError(TAG, "TTS init hadn't resolved after ${INIT_TIMEOUT_MS}ms — giving up on: \"$text\"")
            return
        }
        if (!isReady) {
            AppLogger.logError(TAG, "speak() called but the TTS engine never initialized — dropping: \"$text\"")
            return
        }

        val locale = localeFor(text, language)
        val availability = tts.isLanguageAvailable(locale)
        val setResult = tts.setLanguage(locale)
        AppLogger.log(TAG, "speak(): locale=$locale, isLanguageAvailable=$availability, setLanguage=$setResult, text=\"$text\"")

        val utteranceId = UUID.randomUUID().toString()
        val speakResult = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (speakResult == TextToSpeech.ERROR) {
            AppLogger.logError(TAG, "tts.speak() returned ERROR immediately for utterance $utteranceId")
        }
    }

    fun stop() = tts.stop()

    fun shutdown() = tts.shutdown()

    private fun localeFor(text: String, language: AssistantLanguage): Locale = when (language) {
        AssistantLanguage.ARABIC -> Locale.forLanguageTag("ar")
        AssistantLanguage.ENGLISH -> Locale.US
        AssistantLanguage.AUTO -> if (text.any { it in ARABIC_UNICODE_RANGE }) Locale.forLanguageTag("ar") else Locale.US
    }

    private suspend fun awaitReady() {
        val resolved = synchronized(lock) { initResolved }
        if (resolved) return
        suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                if (initResolved) {
                    cont.resume(Unit)
                } else {
                    pending += { cont.resume(Unit) }
                }
            }
        }
    }

    private companion object {
        val ARABIC_UNICODE_RANGE = '؀'..'ۿ'
    }
}

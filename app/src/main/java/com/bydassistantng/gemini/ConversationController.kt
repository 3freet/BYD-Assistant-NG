package com.bydassistantng.gemini

import androidx.annotation.StringRes
import com.bydassistantng.R
import com.bydassistantng.util.AppLanguage
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.data.SecureCredentials
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.ShellHelperVehicleController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConversationController"
// Model history, per real HTTP responses seen in the app log (not guesswork):
//  - gemini-3.7-flash: consistent HTTP 503 "experiencing high demand" — capacity-constrained this
//    close to its 2026-08-13 GA.
//  - gemini-2.5-flash: consistent HTTP 404 "no longer available to new users... use
//    models/gemini-3.6-flash" — retired outright for this account, not a transient issue at all.
// gemini-3.6-flash is literally the model Google's own 404 named as the replacement, and (GA'd
// 2026-07-21) has had longer to scale capacity than 3.7. Revisit only against another real error.
private const val MODEL = "gemini-3.6-flash"

/** One turn's progress, reported back to the caller (service/UI) as it happens. */
sealed interface TurnStatus {
    data object Thinking : TurnStatus
    data class DispatchingCommand(val displayName: String) : TurnStatus
    data class Speaking(val text: String, val language: AssistantLanguage) : TurnStatus
    data class Failed(val message: String) : TurnStatus

    /** A Live turn finished with nothing further to report — lets a UI that was showing
     * "Listening…" go back to idle even when the turn produced no transcript (e.g. silence). The
     * classic path never emits this: its callers already treat the end of `runTurn` as the end. */
    data object Done : TurnStatus

    /** The mic has (re)opened for the user's next utterance — in a conversation this happens after
     * every reply, without a press. */
    data object Listening : TurnStatus
}

/**
 * Owns one full voice turn: recorded audio in, spoken (well, text-to-speak) reply out, with an
 * optional Gemini-function-call detour through the vehicle-control registry in between. Replaces
 * the old app's local bilingual keyword matcher entirely — Gemini decides whether to reply
 * conversationally or call a function, and can only ever call one that was declared to it.
 *
 * [Mutex]-guarded: a second call while a turn is in flight waits rather than racing it, since both
 * would share the same conversation-less, single-turn history (no multi-turn memory across calls
 * by design — each tap is a fresh, independent request).
 */
@Singleton
class ConversationController @Inject constructor(
    private val geminiClient: GeminiClient,
    private val secureCredentials: SecureCredentials,
    private val preferencesRepository: PreferencesRepository,
    // The same instance the Live path uses, so there is only ever one helper process. It does
    // nothing (no ADB, no process) until a vehicle command is actually dispatched or warmed up.
    private val vehicleController: ShellHelperVehicleController,
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    suspend fun runTurn(audioFile: File, mimeType: String, onStatus: suspend (TurnStatus) -> Unit) {
        val turnId = SystemClock.elapsedRealtime() % 100_000
        val turnStartMs = SystemClock.elapsedRealtime()
        AppLogger.log(TAG, "[turn $turnId] requesting mutex (audio=${audioFile.length()} bytes, mime=$mimeType)")

        mutex.withLock {
            AppLogger.log(TAG, "[turn $turnId] acquired mutex after ${SystemClock.elapsedRealtime() - turnStartMs}ms")
            onStatus(TurnStatus.Thinking)

            val apiKey = secureCredentials.getApiKey()
            if (apiKey.isNullOrBlank()) {
                AppLogger.log(TAG, "[turn $turnId] aborted: no API key configured")
                onStatus(TurnStatus.Failed(text(R.string.classic_no_key)))
                return
            }

            val language = preferencesRepository.assistantLanguage.first()
            // Vehicle commands are only ever declared when the user has explicitly armed vehicle
            // control, so Gemini structurally cannot call one otherwise (navigation is always offered).
            val vehicleControlEnabled = preferencesRepository.vehicleControlEnabled.first()
            val tools = assistantTools(vehicleControlEnabled)

            val encodeStartMs = SystemClock.elapsedRealtime()
            val audioBase64 = try {
                withContext(Dispatchers.IO) { Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP) }
            } catch (e: IOException) {
                AppLogger.logError(TAG, "[turn $turnId] failed to read recorded audio", e)
                onStatus(TurnStatus.Failed(text(R.string.classic_cant_read)))
                return
            }
            AppLogger.log(TAG, "[turn $turnId] encoded audio in ${SystemClock.elapsedRealtime() - encodeStartMs}ms (${audioBase64.length} base64 chars, vehicleControl=$vehicleControlEnabled)")

            val systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = voiceAssistantSystemPrompt(language))))
            val history = mutableListOf(
                GeminiContent(role = "user", parts = listOf(GeminiPart(inlineData = GeminiInlineData(mimeType, audioBase64)))),
            )

            val firstCallStartMs = SystemClock.elapsedRealtime()
            val firstContent = when (val result = geminiClient.generateContent(apiKey, MODEL, GeminiRequest(history, systemInstruction, tools))) {
                is GeminiResult.Failure -> {
                    AppLogger.log(TAG, "[turn $turnId] first call failed after ${SystemClock.elapsedRealtime() - firstCallStartMs}ms: ${result.error}")
                    return onStatus(TurnStatus.Failed(describeError(result)))
                }
                is GeminiResult.Success -> result.content
            }
            AppLogger.log(TAG, "[turn $turnId] first call succeeded after ${SystemClock.elapsedRealtime() - firstCallStartMs}ms")

            val functionCall = firstContent.parts.firstOrNull { it.functionCall != null }?.functionCall
            if (functionCall == null) {
                val text = firstContent.parts.firstOrNull { !it.text.isNullOrBlank() }?.text
                AppLogger.log(TAG, "[turn $turnId] done (text reply, total ${SystemClock.elapsedRealtime() - turnStartMs}ms)")
                onStatus(if (text != null) TurnStatus.Speaking(text, language) else TurnStatus.Failed(text(R.string.classic_empty_reply)))
                return
            }

            AppLogger.log(TAG, "[turn $turnId] function call: ${functionCall.name}(${functionCall.args})")
            val dispatchResponse = VehicleFunctionDispatcher.dispatch(
                functionCall, vehicleControlEnabled, vehicleController, context,
            ) { displayName -> onStatus(TurnStatus.DispatchingCommand(displayName)) }

            history += GeminiContent(role = "model", parts = firstContent.parts)
            history += GeminiContent(
                role = "user",
                parts = listOf(GeminiPart(functionResponse = GeminiFunctionResponse(functionCall.name, dispatchResponse))),
            )

            val secondCallStartMs = SystemClock.elapsedRealtime()
            when (val followUp = geminiClient.generateContent(apiKey, MODEL, GeminiRequest(history, systemInstruction, tools))) {
                is GeminiResult.Failure -> {
                    AppLogger.log(TAG, "[turn $turnId] follow-up call failed after ${SystemClock.elapsedRealtime() - secondCallStartMs}ms: ${followUp.error}")
                    onStatus(TurnStatus.Failed(describeError(followUp)))
                }
                is GeminiResult.Success -> {
                    AppLogger.log(TAG, "[turn $turnId] follow-up call succeeded after ${SystemClock.elapsedRealtime() - secondCallStartMs}ms, total ${SystemClock.elapsedRealtime() - turnStartMs}ms")
                    val text = followUp.content.parts.firstOrNull { !it.text.isNullOrBlank() }?.text
                    onStatus(if (text != null) TurnStatus.Speaking(text, language) else TurnStatus.Failed(text(R.string.classic_empty_reply)))
                }
            }
        }
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = AppLanguage.string(context, id, *args)

    private fun describeError(failure: GeminiResult.Failure): String = when (failure.error) {
        GeminiError.INVALID_API_KEY -> text(R.string.classic_key_rejected)
        GeminiError.PERMISSION_DENIED -> text(R.string.classic_permission_denied, failure.serverMessage?.let { " (\"$it\")" } ?: "")
        GeminiError.RATE_LIMITED -> text(R.string.classic_rate_limited)
        GeminiError.NETWORK_ERROR -> text(R.string.classic_network)
        GeminiError.PROVIDER_ERROR -> text(R.string.classic_provider_error)
        GeminiError.EMPTY_RESPONSE -> text(R.string.classic_empty_response)
    }
}

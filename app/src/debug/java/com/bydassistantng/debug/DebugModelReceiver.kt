package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.data.SecureCredentials
import com.bydassistantng.gemini.GeminiClient
import com.bydassistantng.gemini.GeminiContent
import com.bydassistantng.gemini.GeminiError
import com.bydassistantng.gemini.GeminiPart
import com.bydassistantng.gemini.GeminiRequest
import com.bydassistantng.gemini.GeminiResult
import com.bydassistantng.gemini.assistantTools
import com.bydassistantng.gemini.voiceAssistantSystemPrompt
import com.bydassistantng.util.AppLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** The classic (request/response) path's model, as in ConversationController. */
private const val CLASSIC_MODEL = "gemini-3.6-flash"

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ModelCheckEntryPoint {
    fun credentials(): SecureCredentials
}

/**
 * DEBUG BUILDS ONLY. Sends each utterance as text to the same model, with the same system prompt and the same tool
 * declarations the assistant uses with the vehicle link armed, and logs which function it called ("ModelCheck: …").
 * Nothing is dispatched: it only shows how the model reads the declarations.
 *
 * `adb shell am broadcast -a com.bydassistantng.debug.MODEL_CHECK -n <pkg>/com.bydassistantng.debug.DebugModelReceiver --es utterances '["turn on the driver seat heating"]'`
 */
class DebugModelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val utterances = runCatching {
            Json.parseToJsonElement(intent.getStringExtra("utterances") ?: "[]").jsonArray.map { it.jsonPrimitive.content }
        }.getOrNull() ?: return
        val app = context.applicationContext
        // No goAsync(): a receiver left pending for a minute is killed as unresponsive, and this runs for minutes.
        // The app process stays alive as the host of the accessibility service.
        Thread {
            val key = runBlocking { EntryPointAccessors.fromApplication(app, ModelCheckEntryPoint::class.java).credentials().getApiKey() }
            if (key.isNullOrBlank()) {
                AppLogger.log("ModelCheck", "no API key configured")
                return@Thread
            }
            val client = GeminiClient()
            val tools = assistantTools(vehicleControlEnabled = true, conversation = true)
            val system = GeminiContent(parts = listOf(GeminiPart(text = voiceAssistantSystemPrompt(AssistantLanguage.AUTO, conversation = true))))
            for (text in utterances) {
                val request = GeminiRequest(
                    contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = text)))),
                    systemInstruction = system,
                    tools = tools,
                )
                // The key's rate limit is small: one request every few seconds, and a pause-and-retry when it is hit.
                var outcome = runBlocking { client.generateContent(key, CLASSIC_MODEL, request) }
                var retries = 0
                while (outcome is GeminiResult.Failure && outcome.error == GeminiError.RATE_LIMITED && retries < 3) {
                    retries++
                    Thread.sleep(40_000)
                    outcome = runBlocking { client.generateContent(key, CLASSIC_MODEL, request) }
                }
                val answer = when (val result = outcome) {
                    is GeminiResult.Failure -> "FAILED ${result.error} ${result.serverMessage ?: result.detail?.take(200)}"
                    is GeminiResult.Success -> result.content.parts.joinToString(" | ") { part ->
                        part.functionCall?.let { "${it.name}(${it.args})" } ?: ("text: " + part.text.orEmpty().take(120))
                    }
                }
                AppLogger.log("ModelCheck", "\"$text\" -> $answer")
                Thread.sleep(12_000)
            }
        }.start()
    }
}

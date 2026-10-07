package com.bydassistantng.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for the Live API's `BidiGenerateContent` WebSocket protocol
 * (`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`).
 * Every message is a JSON text frame (never binary — audio travels as base64 inside `inlineData`/
 * `LiveBlob.data`, same convention as the classic REST endpoint). [GeminiContent]/[GeminiTool]/
 * [GeminiFunctionCall] from GeminiModels.kt are reused as-is here — the docs explicitly confirm the
 * same `Tool`/`Content` schema applies to both surfaces.
 */

// ── Outgoing (client -> server) ─────────────────────────────────────────────────────────────

@Serializable
data class LiveClientSetupMessage(val setup: LiveSetup)

@Serializable
data class LiveSetup(
    val model: String,
    val generationConfig: LiveGenerationConfig,
    val systemInstruction: GeminiContent? = null,
    val tools: List<GeminiTool>? = null,
    // Empty objects, not null-able flags: presence of the key is what requests transcription:
    // an absent field means "don't bother," per the setup schema.
    val inputAudioTranscription: JsonObject? = null,
    val outputAudioTranscription: JsonObject? = null,
)

// No default here deliberately: GeminiLiveClient's encodeDefaults=false setting omits any field
// equal to its own default, and this is only ever constructed with listOf("AUDIO") — a defaulted
// value would silently vanish from the outgoing JSON, requesting no audio modality at all from the
// one API call whose entire purpose is audio output.
@Serializable
data class LiveGenerationConfig(
    val responseModalities: List<String>,
    // Null = the server's default voice. (An explicit language code is not offered: the native-audio
    // models choose the language themselves.)
    val speechConfig: LiveSpeechConfig? = null,
)

@Serializable
data class LiveSpeechConfig(val voiceConfig: LiveVoiceConfig)

@Serializable
data class LiveVoiceConfig(val prebuiltVoiceConfig: LivePrebuiltVoiceConfig)

@Serializable
data class LivePrebuiltVoiceConfig(val voiceName: String)

/** The other outgoing message shape once a session is set up — exactly one field populated. */
@Serializable
data class LiveClientMessage(
    val realtimeInput: LiveRealtimeInput? = null,
    val toolResponse: LiveToolResponse? = null,
)

@Serializable
data class LiveRealtimeInput(
    val audio: LiveBlob? = null,
    // "Indicates that the audio stream has ended, e.g. because the microphone was turned off."
    // Only valid with automatic (server-side) activity detection enabled, which is this app's
    // default — manual activityStart/activityEnd are for when that's explicitly disabled instead.
    val audioStreamEnd: Boolean? = null,
)

@Serializable
data class LiveBlob(val data: String, val mimeType: String)

@Serializable
data class LiveToolResponse(val functionResponses: List<LiveFunctionResponse>)

@Serializable
data class LiveFunctionResponse(val id: String? = null, val name: String, val response: JsonObject)

// ── Incoming (server -> client) ─────────────────────────────────────────────────────────────

@Serializable
data class LiveServerMessage(
    val setupComplete: JsonObject? = null,
    val serverContent: LiveServerContent? = null,
    val toolCall: LiveToolCall? = null,
    val toolCallCancellation: JsonObject? = null,
    val goAway: LiveGoAway? = null,
)

@Serializable
data class LiveServerContent(
    val modelTurn: GeminiContent? = null,
    val turnComplete: Boolean? = null,
    // A client message (the user talking again) interrupted the model mid-reply — barge-in.
    // Local playback must stop immediately when this arrives, or the model's old audio keeps
    // playing over the user's new turn.
    val interrupted: Boolean? = null,
    val generationComplete: Boolean? = null,
    val inputTranscription: LiveTranscription? = null,
    val outputTranscription: LiveTranscription? = null,
)

@Serializable
data class LiveTranscription(val text: String? = null)

@Serializable
data class LiveToolCall(val functionCalls: List<GeminiFunctionCall> = emptyList())

@Serializable
data class LiveGoAway(val timeLeft: String? = null)

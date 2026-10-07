package com.bydassistantng.gemini

import android.util.Base64
import com.bydassistantng.util.AppLogger
import com.bydassistantng.util.NetworkDebug
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GeminiLiveClient"
private const val LIVE_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
private const val INPUT_AUDIO_MIME = "audio/pcm;rate=16000"

/** One session's worth of events, delivered in order; [Closed]/[Failed] always end the sequence. */
sealed interface LiveEvent {
    data class Audio(val pcmBase64: String) : LiveEvent
    data class InputTranscript(val text: String) : LiveEvent
    data class OutputTranscript(val text: String) : LiveEvent
    data class ToolCall(val calls: List<GeminiFunctionCall>) : LiveEvent
    data object TurnComplete : LiveEvent
    data object Interrupted : LiveEvent
    data class Failed(val reason: String, val kind: FailureKind = FailureKind.OTHER) : LiveEvent
    data object Closed : LiveEvent
}

/**
 * Raw OkHttp WebSocket client for the Live API's `BidiGenerateContent` protocol — deliberately no
 * SDK, matching this project's plain-OkHttp approach everywhere else. Manages one session at a
 * time: [connectAndSetup] opens a fresh connection, [close] tears it down. Callers own turn-level
 * orchestration ([LiveConversationController]); this class only knows the wire protocol.
 */
@Singleton
class GeminiLiveClient @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<LiveEvent> = events.asSharedFlow()

    // Bumped on every connect and every close. A closed socket's onClosed/onFailure fires
    // asynchronously — possibly after a brand-new session has already subscribed to [events] — and
    // without this, that stale Closed would be delivered to (and tear down) the new session.
    private val generation = AtomicInteger(0)

    /** True from the moment the server confirms setup until the socket fails, closes or is closed —
     * lets a conversation that sat idle between turns find out the server dropped it. */
    @Volatile var isOpen = false
        private set

    /** Why the last [connectAndSetup] failed, or null if it succeeded (or merely timed out with no
     * error — which on a head unit tethered to a phone usually still means the network). */
    @Volatile var lastSetupFailure: FailureKind? = null
        private set

    @Volatile var lastSetupTimedOut = false
        private set

    /**
     * Opens the WebSocket, sends the setup message, and suspends until `setupComplete` arrives or
     * [timeoutMs] elapses. Returns true only if the session is actually ready to receive audio —
     * callers should fall back to the classic REST path on false, not retry indefinitely, since a
     * Live-capable model being a preview model means outright unavailability is a real outcome.
     */
    suspend fun connectAndSetup(
        apiKey: String,
        model: String,
        systemInstruction: GeminiContent,
        tools: List<GeminiTool>?,
        voiceName: String?,
        timeoutMs: Long,
    ): Boolean {
        close()
        lastSetupFailure = null
        lastSetupTimedOut = false
        val ready = CompletableDeferred<Boolean>()
        val myGeneration = generation.incrementAndGet()
        fun isCurrent() = generation.get() == myGeneration

        val baseUrl = if (NetworkDebug.simulateOffline) LIVE_WS_URL.replace("generativelanguage.googleapis.com", "offline.invalid") else LIVE_WS_URL
        val request = Request.Builder().url("$baseUrl?key=$apiKey").build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (!isCurrent()) return
                val setup = LiveClientSetupMessage(
                    setup = LiveSetup(
                        model = "models/$model",
                        generationConfig = LiveGenerationConfig(
                            responseModalities = listOf("AUDIO"),
                            speechConfig = voiceName?.let { LiveSpeechConfig(LiveVoiceConfig(LivePrebuiltVoiceConfig(it))) },
                        ),
                        systemInstruction = systemInstruction,
                        tools = tools,
                        inputAudioTranscription = JsonObject(emptyMap()),
                        outputAudioTranscription = JsonObject(emptyMap()),
                    ),
                )
                val functions = tools?.sumOf { it.functionDeclarations?.size ?: 0 } ?: 0
                val search = tools?.any { it.googleSearch != null } == true
                AppLogger.log(TAG, "WS open, sending setup (model=$model, tools=$functions, search=$search, voice=${voiceName ?: "default"})")
                ws.send(json.encodeToString(LiveClientSetupMessage.serializer(), setup))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (isCurrent()) handleServerMessage(text, ready)
            }

            // The Live endpoint can deliver its JSON payloads as binary frames rather than text
            // frames (Google's own SDKs decode both). OkHttp routes binary frames here, and the
            // default implementation silently drops them — which would mean setupComplete never
            // arrives and every session times out for no visible reason.
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (isCurrent()) handleServerMessage(bytes.utf8(), ready)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent()) return
                AppLogger.logError(TAG, "WS failure (http=${response?.code})", t)
                val kind = ConnectionFailures.classify(t, response?.code)
                if (!ready.isCompleted) lastSetupFailure = kind
                isOpen = false
                ready.complete(false)
                events.tryEmit(LiveEvent.Failed(t.message ?: "WebSocket failure", kind))
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                if (!isCurrent()) return
                AppLogger.log(TAG, "WS closing: code=$code reason=$reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!isCurrent()) return
                AppLogger.log(TAG, "WS closed: code=$code reason=$reason")
                if (!ready.isCompleted && code != 1000) lastSetupFailure = ConnectionFailures.classifyClose(code, reason)
                isOpen = false
                ready.complete(false)
                events.tryEmit(LiveEvent.Closed)
            }
        })

        val outcome = withTimeoutOrNull(timeoutMs) { ready.await() }
        val result = outcome ?: false
        if (outcome == null && isCurrent()) lastSetupTimedOut = true
        if (isCurrent()) isOpen = result
        if (!result) AppLogger.log(TAG, "Live setup did not complete within ${timeoutMs}ms (failure=$lastSetupFailure, timedOut=$lastSetupTimedOut)")
        return result
    }

    fun sendAudioChunk(pcm: ByteArray) {
        val message = LiveClientMessage(realtimeInput = LiveRealtimeInput(audio = LiveBlob(Base64.encodeToString(pcm, Base64.NO_WRAP), INPUT_AUDIO_MIME)))
        webSocket?.send(json.encodeToString(LiveClientMessage.serializer(), message))
    }

    /** Tells the server no more audio is coming for now (e.g. the user tapped to stop) — the
     * server can still be reopened for a later turn by simply sending audio again. */
    fun sendAudioStreamEnd() {
        webSocket?.send(json.encodeToString(LiveClientMessage.serializer(), LiveClientMessage(realtimeInput = LiveRealtimeInput(audioStreamEnd = true))))
    }

    fun sendToolResponses(responses: List<LiveFunctionResponse>) {
        webSocket?.send(json.encodeToString(LiveClientMessage.serializer(), LiveClientMessage(toolResponse = LiveToolResponse(responses))))
    }

    fun close() {
        generation.incrementAndGet() // see [generation]: drop this socket's late callbacks
        isOpen = false
        webSocket?.close(1000, "done")
        webSocket = null
    }

    private fun handleServerMessage(text: String, ready: CompletableDeferred<Boolean>) {
        val message = try {
            json.decodeFromString(LiveServerMessage.serializer(), text)
        } catch (e: Exception) {
            AppLogger.logError(TAG, "Failed to parse server message: $text", e)
            return
        }

        if (message.setupComplete != null) {
            AppLogger.log(TAG, "setupComplete received")
            ready.complete(true)
            return
        }

        message.serverContent?.let { content ->
            content.modelTurn?.parts?.forEach { part ->
                part.inlineData?.let { events.tryEmit(LiveEvent.Audio(it.data)) }
            }
            content.inputTranscription?.text?.let { events.tryEmit(LiveEvent.InputTranscript(it)) }
            content.outputTranscription?.text?.let { events.tryEmit(LiveEvent.OutputTranscript(it)) }
            if (content.interrupted == true) events.tryEmit(LiveEvent.Interrupted)
            if (content.turnComplete == true) events.tryEmit(LiveEvent.TurnComplete)
        }

        message.toolCall?.let { events.tryEmit(LiveEvent.ToolCall(it.functionCalls)) }
        message.goAway?.let { AppLogger.log(TAG, "GoAway received: timeLeft=${it.timeLeft}") }
    }
}

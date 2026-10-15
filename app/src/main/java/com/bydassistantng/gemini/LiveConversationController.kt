package com.bydassistantng.gemini

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.bydassistantng.BuildConfig
import com.bydassistantng.R
import com.bydassistantng.audio.BargeInDetector
import com.bydassistantng.cluster.ClusterOptions
import com.bydassistantng.audio.DipConfirmer
import com.bydassistantng.audio.Earcon
import com.bydassistantng.audio.Earcons
import com.bydassistantng.audio.EndOfSpeechWait
import com.bydassistantng.audio.LivePcmPlayer
import com.bydassistantng.audio.LivePcmRecorder
import com.bydassistantng.audio.ExteriorVolume
import com.bydassistantng.audio.PlaySoundOutsideTool
import com.bydassistantng.audio.SpeakOutsideTool
import com.bydassistantng.audio.SpeechEndpointer
import com.bydassistantng.audio.UtteranceHold
import com.bydassistantng.audio.pcm16Rms
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.util.NetworkState
import com.bydassistantng.data.AssistantVoices
import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.memory.AddOutcome
import com.bydassistantng.memory.Exchange
import com.bydassistantng.memory.ForgetMemoryTool
import com.bydassistantng.memory.MemoryContext
import com.bydassistantng.memory.MemoryFact
import com.bydassistantng.memory.MemoryRepository
import com.bydassistantng.memory.RecentExchanges
import com.bydassistantng.memory.RememberTool
import com.bydassistantng.data.SecureCredentials
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.media.PlayMediaTool
import com.bydassistantng.media.PlaybackFollowUp
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.DriveModeRepeatGuard
import com.bydassistantng.vehicle.DriveModeSwitch
import com.bydassistantng.vehicle.SetDriveModeTool
import com.bydassistantng.vehicle.ShellHelperVehicleController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiveConversationController"

/** Why [LiveConversationController.start] gave up, when the caller needs to tell the user something
 * specific rather than quietly trying the classic pipeline. */
enum class StartFailure { NONE, NO_API_KEY, NO_NETWORK, API_KEY_REJECTED, BUSY, OTHER }

// gemini-3.1-flash-live-preview (what this was first built against) is now listed by Google as a
// "legacy, superseded" preview model; gemini-3.8-live went GA on 2026-09-15 and is the documented
// default for low-latency voice agents. Verified against real sessions on the head unit.
private const val MODEL = "gemini-3.8-live"
private const val SETUP_TIMEOUT_MS = 4_000L

// Upper bound on how long the mic stays open for a single utterance. Without it, a server that never
// detects an end of speech would hold the microphone indefinitely.
private const val MAX_LISTEN_MS = 20_000L

// How long the mic waits for the user to start speaking before the conversation ends by itself — so
// walking away (or just not having anything to say) doesn't leave the assistant listening forever.
private const val NO_SPEECH_TIMEOUT_MS = 12_000L

// How long to wait for *any* server activity once the user has finished. Seen on a head unit: a
// turn with no speech never gets a reply at all (not even a turn-complete), which left the UI on
// "Thinking…" indefinitely. Re-armed on every event, so a long reply that's still streaming never
// trips it.
private const val RESPONSE_TIMEOUT_MS = 12_000L

// After a reply has finished playing, a beat before the "go ahead" chime, so it doesn't land on top of
// the tail of the assistant's own voice.
private const val REOPEN_DELAY_MS = 500L

// The exterior speaker's helper starts this long after the vehicle helper, not beside it.
private const val HELPER_STAGGER_MS = 1_500L

// After a conversation the connection to the server is refreshed this often, this many times (about half an hour).
private const val KEEP_WARM_INTERVAL_MS = 4 * 60_000L
private const val KEEP_WARM_REFRESHES = 7

// After a barge-in the user's utterance may turn out to have been nothing (a cough, a bump the detector
// took for speech): the server then never answers, so don't make the user wait the full time to find out.
private const val BARGE_IN_RESPONSE_TIMEOUT_MS = 5_000L

// The first wait after the user finishes a sentence: if the server has not made a sound at all (not even the
// transcript) by now it is not going to. In 313 answered turns the first sign came within 2.2 s at worst, and a
// silent turn used to cost the full 12 s of dead air. Once the server shows signs of life the usual timeout applies.
private const val FIRST_RESPONSE_TIMEOUT_MS = 5_000L

// Consecutive turns the server never answers before the conversation is given up on, rather than
// listening again forever.
private const val MAX_UNANSWERED_TURNS = 2

// After the server refuses a session because of web search, how long before search is tried again.
private const val SEARCH_RETRY_AFTER_MS = 30 * 60_000L

// A barge-in sends the server what was heard just before it was detected, so the first words of the
// interruption aren't lost: 50ms chunks. It has to cover the ~250ms the detector needs to be sure PLUS the
// few hundred ms of the check below, so 16 = 0.8s.
private const val PRE_ROLL_CHUNKS = 16

// When the detector thinks the user is talking over a reply, the reply is first turned down to this
// fraction of its volume and the mic is watched for a moment (see [DipConfirmer]). A sound the speakers
// caused falls with it; the user's voice does not. -18 dB.
private const val DIP_VOLUME = 0.12f

// Speaker -> mic delay is ~100-150ms, smeared by the room; the reference window covers it.
private const val REFERENCE_WINDOW_START_MS = 350L
private const val REFERENCE_WINDOW_END_MS = 40L

// Real speech through the processed mic peaks at 5,000+; the "end of speech" the endpointer sometimes
// finds in cabin noise peaks well under 1,000. Anything fainter than this isn't a sentence, so it's
// ignored instead of being sent off and waited on.
private const val MIN_UTTERANCE_PEAK = 1_500

// An interruption spoken while the server is still finishing the reply it was cut off from is held back
// until that reply's turn completes (see [UtteranceHold]). If neither "complete" nor "interrupted" shows up,
// this is the longest to wait.
private const val HOLD_MAX_MS = 5_000L

// Sent after held speech that had already ended, so the server's voice-activity detection hears the pause it
// needs (800ms of 50ms chunks), and the size of one such chunk (50ms of 16kHz mono 16-bit).
private const val SILENCE_TAIL_CHUNKS = 16
private const val SILENCE_CHUNK_BYTES = 1_600

// A server's "interrupted" is followed within moments by the turn-complete of the turn it cut short; that
// one must not be mistaken for the end of the reply that follows.
private const val INTERRUPTED_TURN_COMPLETE_WINDOW_MS = 1_500L

/**
 * Live-API voice conversation: one WebSocket session that lasts the whole conversation, so the model
 * remembers what was said earlier. The mic runs for the whole conversation too, and what happens to its
 * audio depends on the [Gate]:
 *
 *  - [Gate.OPEN] — the user's turn: audio goes to the server, and [SpeechEndpointer] watches for them
 *    to stop.
 *  - [Gate.SILENT] — they've finished: only digital silence goes up, so the server's own voice-activity
 *    detection sees the pause it needs and answers.
 *  - [Gate.BLOCKED] — the assistant is answering: **nothing** goes up. Left open, the car's speakers feed
 *    the reply back into the mic and the server takes that for the user interrupting, cutting the reply
 *    off ~2s in. Instead [BargeInDetector] listens locally and, if the user really does talk over the
 *    reply, cuts it off at once, hands the server what they said (including the half-second before it was
 *    noticed) and goes back to [Gate.OPEN] — a real interruption, not a guess by the server.
 *
 * Then the gate reopens by itself (after the reply has been heard), and the conversation goes on until
 * the user presses the button, says they're done (the model calls `end_conversation`), nobody speaks
 * for [NO_SPEECH_TIMEOUT_MS], or two turns in a row get no reply.
 *
 * [start] itself decides whether Live is even usable: a failed/timed-out setup means the caller should
 * immediately fall back to [ConversationController] for this turn, not retry.
 */
@Singleton
class LiveConversationController @Inject constructor(
    private val liveClient: GeminiLiveClient,
    private val recorder: LivePcmRecorder,
    private val player: LivePcmPlayer,
    private val earcons: Earcons,
    private val vehicleController: ShellHelperVehicleController,
    private val secureCredentials: SecureCredentials,
    private val preferencesRepository: PreferencesRepository,
    private val memory: MemoryRepository,
    // Present only in debug builds (see [DriveModeSwitch]).
    driveModes: Optional<DriveModeSwitch>,
    @ApplicationContext private val context: Context,
) {
    private val driveModeSwitch: DriveModeSwitch? = driveModes.orElse(null)
    private val driveModeRepeats = DriveModeRepeatGuard()

    // The opt-in car features, as switched in Advanced settings when this conversation started (both off by default).
    private var exteriorSpeakerForSession = false
    private var driveModesForSession = false

    // The cluster screen (a development build): its switch, and what happens to BYD Maps afterwards.
    private var clusterForSession = ClusterOptions.OFF

    private enum class Gate { OPEN, SILENT, BLOCKED }

    private var vehicleControlEnabledForSession = false
    private var languageForSession = AssistantLanguage.AUTO
    private var dialectForSession = ArabicDialect.MATCH
    private var webSearchForSession = false
    private var voiceForSession: String? = null
    private var searchBlockedUntilMs = 0L

    /** Why the last [start] returned false, and what to tell the user about it (in their language). */
    @Volatile var lastStartFailure = StartFailure.NONE
        private set
    @Volatile var lastStartMessage = ""
        private set
    @Volatile private var bargeInEnabled = true
    private var onStatus: (suspend (TurnStatus) -> Unit)? = null

    // Kept so a session the server dropped between turns can be re-opened transparently.
    private var sessionApiKey: String? = null
    private var sessionInstruction: GeminiContent? = null
    private var sessionTools: List<GeminiTool>? = null

    private var eventJob: Job? = null
    private var listenCapJob: Job? = null
    private var responseWatchdog: Job? = null
    private var doneChimeJob: Job? = null

    /** Waits for the reply to finish playing, then either reopens the gate or ends the conversation. */
    private var nextStepJob: Job? = null

    @Volatile private var conversationActive = false

    // Set when the model called end_conversation: the conversation ends once its goodbye has played.
    private var endAfterReply = false

    // A turn that involves a function call completes twice: once when the model hands over the call,
    // and again after it has spoken the result. Only the second is the end of the exchange.
    private var awaitingSpokenReply = false
    private var unansweredTurns = 0

    @Volatile private var gate = Gate.BLOCKED
    // The assistant's memory as this conversation uses it, read when it starts (see [MemoryRepository]).
    private var longTermForSession = false
    private var shortTermForSession = false
    private var factsForSession: List<MemoryFact> = emptyList()
    private var recentForSession: List<Exchange> = emptyList()

    // How many chunks of the user's audio went to the server this turn (for working out why a turn goes unanswered).
    @Volatile private var chunksThisTurn = 0

    // The exchange in progress: what the user said and which functions were called, until its reply is complete.
    private val pendingUser = StringBuilder()
    private val pendingActions = mutableListOf<String>()

    // How long a silence after speech has to last before the turn is over (a setting, read at the start of each conversation).
    @Volatile private var silenceEndMsForSession = EndOfSpeechWait.DEFAULT_MS
    private var endpointer = newEndpointer()
    private val bargeInDetector = BargeInDetector()

    // Between "the detector thinks that's the user" and "it is, cut the reply" / "it isn't, carry on": the
    // reply is turned down and the mic watched. Only touched from the capture thread and the resets below.
    private val dipConfirmer = DipConfirmer()
    @Volatile private var dipping = false

    // The last stretch of mic audio while BLOCKED, so a barge-in can include what led up to it. Only
    // ever touched from the mic's capture thread.
    private val preRoll = ArrayDeque<ByteArray>()

    // The user's interruption, kept locally while the server finishes the reply it cut off.
    private val utteranceHold = UtteranceHold()
    private var holdJob: Job? = null

    // Per-reply state used to decide whether a barge-in is possible yet.
    @Volatile private var replyAudioStarted = false
    @Volatile private var turnCompleted = false

    // After the user cuts a reply off locally, whatever of it is still in flight from the server must not
    // start playing again.
    @Volatile private var discardReply = false
    @Volatile private var interruptedAtMs = 0L
    @Volatile private var responseTimeoutMs = RESPONSE_TIMEOUT_MS
    private var lastMicErrorLogMs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Output transcripts arrive as incremental fragments, not cumulative text — reporting each one
    // on its own would flicker the UI one word at a time, so they're accumulated per turn.
    private val transcript = StringBuilder()

    private fun newEndpointer() = SpeechEndpointer(silenceEndMs = silenceEndMsForSession)

    /** True while the mic is open for the user to speak. */
    val isListening: Boolean get() = recorder.isRecording && gate == Gate.OPEN

    /** True from the moment a conversation starts until it ends — through listening, thinking and the
     * assistant speaking. This, not [isListening], is what decides whether a button press means "end it"
     * or "start one". */
    val inConversation: Boolean get() = conversationActive

    // Refreshes the pooled connection to the server for a while after a conversation, so a press some minutes
    // later still finds it open (an idle pooled connection is dropped after about five minutes).
    private var keepWarmJob: Job? = null

    private fun keepConnectionWarm() {
        keepWarmJob?.cancel()
        liveClient.preconnect()
        keepWarmJob = scope.launch {
            repeat(KEEP_WARM_REFRESHES) {
                delay(KEEP_WARM_INTERVAL_MS)
                if (!conversationActive) liveClient.preconnect()
            }
        }
    }

    init {
        keepConnectionWarm()
        // A "play X" whose last step could not start anything says so with the error tone.
        PlaybackFollowUp.onNotStarted = { earcons.play(Earcon.ERROR) }
        PlaybackFollowUp.isConversationActive = { conversationActive }
    }

    /** Attempts to open a Live session and start the conversation. Returns false if the session
     * couldn't be established at all (model unavailable, access denied, network error, timeout) —
     * no audio is lost in that case, since capture only starts after setup succeeds, so the caller
     * can fall back to the classic pipeline for this turn with a clean slate. */
    suspend fun start(onStatus: suspend (TurnStatus) -> Unit): Boolean {
        // Before close(), which would otherwise start a press that is waiting for the conversation to end.
        PlaybackFollowUp.cancel()
        keepWarmJob?.cancel()
        close()
        // close() deliberately lets queued audio finish, but starting means the user pressed over any
        // previous reply and wants it to stop.
        player.stopAndFlush()
        this.onStatus = onStatus
        transcript.clear()
        endAfterReply = false
        awaitingSpokenReply = false
        discardReply = false
        unansweredTurns = 0

        lastStartFailure = StartFailure.NONE
        lastStartMessage = ""
        val startedAt = SystemClock.elapsedRealtime()
        languageForSession = preferencesRepository.assistantLanguage.first()
        val apiKey = secureCredentials.getApiKey()
        if (apiKey.isNullOrBlank()) {
            lastStartFailure = StartFailure.NO_API_KEY
            lastStartMessage = AppLanguage.string(context, UserMessages.NO_API_KEY)
            return false
        }

        vehicleControlEnabledForSession = preferencesRepository.vehicleControlEnabled.first()
        bargeInEnabled = preferencesRepository.bargeInEnabled.first()
        dialectForSession = preferencesRepository.arabicDialect.first()
        // Off while a recent session showed the server refusing it (see [searchBlockedUntilMs]).
        webSearchForSession = preferencesRepository.webSearchEnabled.first() && SystemClock.elapsedRealtime() >= searchBlockedUntilMs
        voiceForSession = AssistantVoices.valid(preferencesRepository.assistantVoice.first())
        silenceEndMsForSession = EndOfSpeechWait.clamp(preferencesRepository.endOfSpeechWaitMs.first())
        longTermForSession = preferencesRepository.longTermMemoryEnabled.first()
        shortTermForSession = preferencesRepository.shortTermMemoryEnabled.first()
        exteriorSpeakerForSession = preferencesRepository.exteriorSpeakerEnabled.first()
        driveModesForSession = preferencesRepository.driveModesEnabled.first()
        clusterForSession = ClusterOptions(
            enabled = BuildConfig.DEBUG && preferencesRepository.clusterScreenEnabled.first(),
            keepMapsDisabled = preferencesRepository.clusterKeepMapsDisabled.first(),
        )
        factsForSession = if (longTermForSession) memory.currentFacts() else emptyList()
        recentForSession = if (shortTermForSession) memory.recentExchanges() else emptyList()
        pendingUser.clear()
        pendingActions.clear()
        if (factsForSession.isNotEmpty() || recentForSession.isNotEmpty()) {
            AppLogger.log(TAG, "Memory: ${factsForSession.size} remembered fact(s), ${recentForSession.size} recent exchange(s) carried in")
        }

        sessionApiKey = apiKey
        buildSession()
        val settingsMs = SystemClock.elapsedRealtime() - startedAt

        var opened = openSession()
        val connectedMs = SystemClock.elapsedRealtime() - startedAt - settingsMs
        // A server that turns the setup down must not leave the user with no assistant at all. The extras are
        // dropped one at a time, search first, so the log says which one it was: "exceeded your current quota"
        // came back for a key that cannot use Google Search while everything else on it worked.
        if (!opened && serverRefusedSetup() && webSearchForSession) {
            AppLogger.log(TAG, "The server refused the session with web search on (${liveClient.lastSetupFailure}) — retrying without it")
            webSearchForSession = false
            buildSession()
            opened = openSession()
            if (opened) {
                searchBlockedUntilMs = SystemClock.elapsedRealtime() + SEARCH_RETRY_AFTER_MS
                AppLogger.log(TAG, "It works without web search, so the server is refusing search for this key (quota or billing?) — skipping it for ${SEARCH_RETRY_AFTER_MS / 60_000} minutes")
            }
        }
        if (!opened && serverRefusedSetup() && voiceForSession != null) {
            AppLogger.log(TAG, "The server refused the session (${liveClient.lastSetupFailure}) — retrying without the custom voice")
            voiceForSession = null
            buildSession()
            opened = openSession()
        }
        if (!opened) {
            AppLogger.log(TAG, "Live session unavailable — caller should fall back to the classic pipeline")
            recordStartFailure()
            liveClient.close()
            return false
        }

        if (!recorder.start(onChunk = ::onMicChunk)) {
            AppLogger.logError(TAG, "Live session ready but mic capture failed to start")
            close()
            return false
        }
        openGate()
        conversationActive = true
        AppLogger.log(
            TAG,
            "Ready to listen ${SystemClock.elapsedRealtime() - startedAt}ms after start " +
                "(settings ${settingsMs}ms, connection and setup ${connectedMs}ms)",
        )
        // The vehicle helper is started now, while the user is still speaking, so the first command does not wait
        // for it to boot — but only once the connection is up: two JVMs starting at the same moment as the
        // connection's TLS handshake and setup were competing with it for the head unit's CPU. The exterior
        // speaker's helper follows the vehicle helper rather than starting beside it.
        if (vehicleControlEnabledForSession) warmUpHelpers()
        return true
    }

    private fun warmUpHelpers() {
        vehicleController.warmUp()
        // Nothing for the exterior speaker is started unless it is switched on.
        if (exteriorSpeakerForSession) {
            scope.launch {
                delay(HELPER_STAGGER_MS)
                player.prepareOutside()
            }
        }
    }

    /** The tools and instructions for this session, from the settings read at [start]. */
    private fun buildSession() {
        sessionTools = assistantTools(
            vehicleControlEnabledForSession,
            conversation = true,
            webSearch = webSearchForSession,
            exteriorSpeaker = player.canSpeakOutside && exteriorSpeakerForSession,
            soundEffects = if (exteriorSpeakerForSession) player.soundEffects else emptyList(),
            memoryTools = longTermForSession,
            driveModes = driveModeSwitch != null && driveModesForSession,
            clusterScreen = clusterForSession.enabled,
        )
        sessionInstruction = GeminiContent(
            parts = listOf(
                GeminiPart(
                    text = voiceAssistantSystemPrompt(
                        languageForSession,
                        conversation = true,
                        dialect = dialectForSession,
                        webSearch = webSearchForSession,
                        memory = MemoryContext(tools = longTermForSession, facts = factsForSession, recent = recentForSession),
                    ),
                ),
            ),
        )
    }

    /** True when the server itself turned the setup down (for whatever reason: unsupported option, quota, key),
     * as opposed to the network being down or nothing coming back at all. */
    private fun serverRefusedSetup(): Boolean =
        !liveClient.lastSetupTimedOut && liveClient.lastSetupFailure.let { it != null && it != FailureKind.NO_NETWORK }

    /** Works out, from how the setup failed, what the user should be told. */
    private fun recordStartFailure() {
        val kind = liveClient.lastSetupFailure
        val offline = kind == FailureKind.NO_NETWORK || (liveClient.lastSetupTimedOut && !NetworkState.isOnline(context))
        when {
            offline -> {
                lastStartFailure = StartFailure.NO_NETWORK
                lastStartMessage = UserMessages.text(context, FailureKind.NO_NETWORK)
            }
            kind == FailureKind.API_KEY_REJECTED -> {
                lastStartFailure = StartFailure.API_KEY_REJECTED
                lastStartMessage = UserMessages.text(context, kind)
            }
            kind == FailureKind.BUSY -> {
                lastStartFailure = StartFailure.BUSY
                lastStartMessage = UserMessages.text(context, kind)
            }
            else -> {
                lastStartFailure = StartFailure.OTHER
                lastStartMessage = UserMessages.text(context, FailureKind.OTHER)
            }
        }
        AppLogger.log(TAG, "Start failure: $lastStartFailure (setup=$kind, timedOut=${liveClient.lastSetupTimedOut})")
    }

    /** Opens (or re-opens) the WebSocket and starts listening to its events. */
    private suspend fun openSession(): Boolean {
        val apiKey = sessionApiKey ?: return false
        val ready = liveClient.connectAndSetup(apiKey, MODEL, sessionInstruction ?: return false, sessionTools, voiceForSession, SETUP_TIMEOUT_MS)
        if (!ready) return false
        eventJob?.cancel()
        eventJob = scope.launch {
            liveClient.eventFlow.collect { event ->
                // One bad event must not kill the process: an uncaught exception in here used to crash the app.
                try {
                    handleEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.logError(TAG, "Error handling $event", e)
                }
            }
        }
        return true
    }

    /** The user's turn begins: the mic's audio goes to the server, with fresh end-of-speech detection, the
     * "go ahead" tone, and the timers that stop a silent or endless utterance. */
    private fun openGate() {
        endpointer = newEndpointer()
        awaitingSpokenReply = false
        replyAudioStarted = false
        turnCompleted = false
        discardReply = false
        chunksThisTurn = 0
        endDip()
        responseTimeoutMs = RESPONSE_TIMEOUT_MS
        holdJob?.cancel()
        utteranceHold.discard()
        synchronized(preRoll) { preRoll.clear() }
        gate = Gate.OPEN
        earcons.play(Earcon.LISTENING)
        armListenTimeouts(afterBargeIn = false)
    }

    /** Whether a sentence (not just cabin noise, which the filter below ignores) has been heard this turn. */
    private fun heardRealSpeech(): Boolean =
        endpointer.speechDetected && (!recorder.usesVoiceProcessing || endpointer.peak >= MIN_UTTERANCE_PEAK)

    private fun armListenTimeouts(afterBargeIn: Boolean) {
        listenCapJob?.cancel()
        listenCapJob = scope.launch {
            if (!afterBargeIn) {
                delay(NO_SPEECH_TIMEOUT_MS)
                // Only reached while nothing has answered yet: once the server replies the gate closes and
                // this job is cancelled.
                if (isListening && !heardRealSpeech()) {
                    AppLogger.log(TAG, "Nobody spoke for ${NO_SPEECH_TIMEOUT_MS}ms — ending the conversation")
                    endConversation()
                    return@launch
                }
                delay(MAX_LISTEN_MS - NO_SPEECH_TIMEOUT_MS)
            } else {
                delay(MAX_LISTEN_MS)
            }
            if (isListening) {
                if (!heardRealSpeech()) {
                    // Only ever faint sounds in all that time (each one was ignored): there is nothing to send.
                    AppLogger.log(TAG, "Only faint sounds for ${MAX_LISTEN_MS}ms — ending the conversation")
                    endConversation()
                } else {
                    AppLogger.log(TAG, "No end of turn after ${MAX_LISTEN_MS}ms — closing the mic")
                    stopListening()
                }
            }
        }
    }

    /** Called ~every 50ms from the mic's capture thread, whatever the gate. */
    private fun onMicChunk(chunk: ByteArray) {
        try {
            handleMicChunk(chunk)
        } catch (e: Throwable) {
            // Called 20 times a second on the capture thread, where an uncaught exception kills the app.
            val now = SystemClock.elapsedRealtime()
            if (now - lastMicErrorLogMs > 5_000) {
                lastMicErrorLogMs = now
                AppLogger.logError(TAG, "Error handling a mic chunk", e)
            }
        }
    }

    private fun handleMicChunk(chunk: ByteArray) {
        when (gate) {
            Gate.OPEN -> {
                bargeInDetector.observeAmbient(pcm16Rms(chunk))
                if (!utteranceHold.offer(chunk)) {
                    liveClient.sendAudioChunk(chunk)
                    chunksThisTurn++
                }
                if (endpointer.onChunk(pcm16Rms(chunk))) {
                    if (recorder.usesVoiceProcessing && endpointer.peak < MIN_UTTERANCE_PEAK) {
                        // Cabin noise, not a sentence: keep listening rather than sending it off and
                        // waiting up to 12s for an answer that will never come.
                        AppLogger.log(TAG, "Ignored a faint sound (${endpointer.summary()})")
                        endpointer = newEndpointer()
                    } else {
                        scope.launch {
                            if (isListening) {
                                AppLogger.log(TAG, "User stopped speaking — ending the turn (${endpointer.summary()}, sent $chunksThisTurn audio chunks)")
                                VehicleFunctionDispatcher.newUserTurn()
                                stopListening()
                            }
                        }
                    }
                }
            }
            Gate.SILENT -> {
                bargeInDetector.observeAmbient(pcm16Rms(chunk))
                // While an interruption is held back the silence tail is added when it is released.
                if (!utteranceHold.isHolding) liveClient.sendAudioChunk(ByteArray(chunk.size))
            }
            Gate.BLOCKED -> {
                synchronized(preRoll) {
                    preRoll.addLast(chunk)
                    while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                }
                if (dipping) {
                    checkDip(pcm16Rms(chunk))
                } else if (bargeInEnabled && recorder.usesVoiceProcessing && bargeInPossible()) {
                    val now = SystemClock.elapsedRealtime()
                    val reference = player.referenceRms(now - REFERENCE_WINDOW_START_MS, now - REFERENCE_WINDOW_END_MS)
                    if (bargeInDetector.onChunk(pcm16Rms(chunk), reference)) beginDip()
                }
            }
        }
    }

    /** Talking over the assistant only makes sense once there is a reply to talk over (or one has just
     * ended): while it is still working on a function call there is nothing to interrupt. */
    // Not while the exterior speaker is playing: the cabin does not hear that as a reply, and it is loud enough
    // to reach the mic, where it was taken for the user talking over the assistant and cut the announcement off.
    private fun bargeInPossible(): Boolean = (replyAudioStarted || turnCompleted) && !player.exteriorSoundActive

    /** The detector thinks the user is talking over the reply. Don't cut it yet: turn it right down and see
     * whether the mic stays loud (the user) or falls away (a burst of noise, the speakers' own echo). */
    private fun beginDip() {
        AppLogger.log(TAG, "Possible barge-in — turning the reply down to check (${bargeInDetector.summary()})")
        dipConfirmer.begin()
        dipping = true
        player.setVolume(DIP_VOLUME)
    }

    private fun checkDip(micRms: Int) {
        when (dipConfirmer.onChunk(micRms)) {
            DipConfirmer.Verdict.PENDING -> Unit
            DipConfirmer.Verdict.CONFIRMED -> bargeIn()
            DipConfirmer.Verdict.REJECTED -> {
                AppLogger.log(TAG, "Not the user — the sound died away once the reply was turned down; carrying on")
                bargeInDetector.noteFalseAlarm()
                endDip()
            }
        }
    }

    /** Back to normal volume, whatever the check ended in (or a new reply / turn started under it). */
    private fun endDip() {
        dipping = false
        player.setVolume(1f)
    }

    /** The user spoke over the assistant. Stop it, and carry on as their turn. */
    private fun bargeIn() {
        AppLogger.log(TAG, "Barge-in: the user is speaking over the assistant (${bargeInDetector.summary()})")
        // Cut the reply off at once, and anything of it still on its way from the server. The flag goes
        // up BEFORE the flush: the other way round, a chunk arriving in between slipped through and played.
        discardReply = !turnCompleted
        pendingUser.clear()
        pendingActions.clear()
        player.stopAndFlush()
        endDip() // the next reply starts at full volume
        transcript.clear()
        awaitingSpokenReply = false
        endAfterReply = false
        unansweredTurns = 0
        nextStepJob?.cancel()
        responseWatchdog?.cancel()
        doneChimeJob?.cancel()

        // What was heard just before it was noticed, then live — except that if the server is still finishing
        // the reply that was cut off, speech sent now is ignored or comes back as a fragment, so it is kept
        // until the server is ready.
        val heard = synchronized(preRoll) { preRoll.toList().also { preRoll.clear() } }
        if (discardReply) {
            utteranceHold.begin(heard)
            AppLogger.log(TAG, "The server is still finishing the reply that was cut off — holding the interruption")
            holdJob?.cancel()
            holdJob = scope.launch {
                delay(HOLD_MAX_MS)
                releaseHeldSpeech("waited ${HOLD_MAX_MS}ms")
            }
        } else {
            heard.forEach { liveClient.sendAudioChunk(it) }
        }
        endpointer = newEndpointer().also { it.beginMidSpeech(bargeInDetector.ambient) }
        replyAudioStarted = false
        turnCompleted = false
        responseTimeoutMs = BARGE_IN_RESPONSE_TIMEOUT_MS
        gate = Gate.OPEN
        armListenTimeouts(afterBargeIn = true)
        scope.launch { onStatus?.invoke(TurnStatus.Listening) }
    }

    /**
     * Sends the interruption that was being held back — in order, followed by a silence tail if the user has
     * already finished speaking so the server hears the pause — and stops dropping the server's output: what
     * comes next is the answer to it. Called when the old reply's turn completes, when the server reports the
     * interruption, or after [HOLD_MAX_MS]; whichever happens first.
     */
    private fun releaseHeldSpeech(reason: String) {
        if (!utteranceHold.isHolding) return
        holdJob?.cancel()
        discardReply = false
        val chunks = utteranceHold.release { liveClient.sendAudioChunk(it) }
        if (gate == Gate.SILENT) {
            repeat(SILENCE_TAIL_CHUNKS) { liveClient.sendAudioChunk(ByteArray(SILENCE_CHUNK_BYTES)) }
            // The "no answer" timer started when the user stopped talking, which may have been well before the
            // server actually received any of it: count from now.
            armResponseWatchdog()
        }
        AppLogger.log(TAG, "Released the held interruption ($chunks chunks, $reason)")
    }

    /** Ends the user's utterance (the listening cap, or the end of speech seen by [endpointer]) — the
     * server's own voice-activity detection also ends it on its own. The conversation carries on:
     * this is "I've finished talking", not "stop". */
    fun stopListening() {
        if (!isListening) return
        gate = Gate.SILENT
        listenCapJob?.cancel()
        // "Got it, thinking" — the only audible sign between finishing a sentence and the reply.
        earcons.play(Earcon.THINKING)
        // Without this the UI keeps saying "Listening…" until the model's first transcript arrives.
        scope.launch { onStatus?.invoke(TurnStatus.Thinking) }
        armResponseWatchdog(firstWait = true)
    }

    /** Ends the whole conversation right now: stops the mic and any reply being spoken, closes the
     * session, and says so with the "done" tone. Safe to call at any time. */
    fun endConversation() {
        AppLogger.log(TAG, "Conversation ended")
        player.stopAndFlush()
        close()
        // The next press finds a connection to the server already open (see [GeminiLiveClient.preconnect]).
        keepConnectionWarm()
        earcons.play(Earcon.DONE)
        scope.launch { onStatus?.invoke(TurnStatus.Done) }
    }

    /**
     * The model asked to say something outside the car: switch the car over, then let it speak. Everything it
     * says from here to the end of its turn is heard on the exterior speaker only.
     *
     * When the car's helper is already running the switch is made while the model is starting to speak (the
     * audio waits for it), so the announcement is not delayed by it; otherwise the answer waits for the switch.
     */
    private suspend fun speakOutside(call: GeminiFunctionCall, report: suspend (TurnStatus) -> Unit): JsonObject {
        val message = SpeakOutsideTool.message(call) ?: return SpeakOutsideTool.refused("there was no message to say")
        if (!vehicleControlEnabledForSession || !exteriorSpeakerForSession || !player.canSpeakOutside) {
            return SpeakOutsideTool.refused("the exterior speaker is not available")
        }
        val volume = SpeakOutsideTool.volume(call) ?: preferencesRepository.exteriorVolume.first()
        report(TurnStatus.DispatchingCommand(AppLanguage.string(context, R.string.banner_speak_outside)))
        if (!player.beginOutside(volume, waitForSwitch = !player.outsideReady)) {
            return SpeakOutsideTool.refused("the car did not switch to the exterior speaker")
        }
        AppLogger.log(TAG, "Speaking outside the car (${volume.word}): \"$message\"")
        return SpeakOutsideTool.ready(message)
    }

    /** The model asked for a sound effect outside the car: it plays on its own track, and the cabin carries on. */
    private suspend fun playSoundOutside(call: GeminiFunctionCall, report: suspend (TurnStatus) -> Unit): JsonObject {
        val effects = player.soundEffects
        if (!vehicleControlEnabledForSession || !exteriorSpeakerForSession || effects.isEmpty()) {
            return PlaySoundOutsideTool.refused("the exterior speaker is not available")
        }
        val effect = PlaySoundOutsideTool.effect(call, effects) ?: return PlaySoundOutsideTool.refused("there is no such sound")
        val volume = PlaySoundOutsideTool.volume(call) ?: preferencesRepository.exteriorVolume.first()
        report(TurnStatus.DispatchingCommand(AppLanguage.string(context, R.string.banner_play_sound_outside)))
        AppLogger.log(TAG, "Playing \"${effect.name}\" outside the car (${volume.word})")
        scope.launch {
            val pcm = player.renderEffect(effect.name)
            val played = pcm != null && try {
                player.playEffectOutside(pcm, volume)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.logError(TAG, "Sound effect failed", e)
                false
            }
            if (!played) {
                AppLogger.log(TAG, "Could not play \"${effect.name}\" on the exterior speaker")
                earcons.play(Earcon.ERROR)
            }
        }
        return PlaySoundOutsideTool.playing(effect)
    }

    /**
     * The model asked for a drive mode. The car decides: the answer says which mode it is really in afterwards,
     * or why nothing was sent (moving, no link to the car).
     */
    private suspend fun setDriveMode(call: GeminiFunctionCall, report: suspend (TurnStatus) -> Unit): JsonObject {
        val mode = SetDriveModeTool.mode(call) ?: return SetDriveModeTool.refused("that is not a mode the car can be asked for")
        val switch = driveModeSwitch
        if (!vehicleControlEnabledForSession || !driveModesForSession || switch == null) {
            return SetDriveModeTool.refused("drive modes are not available")
        }
        // The model sometimes asks again straight after a success: answer from the first result, without the car.
        driveModeRepeats.repeatOf(mode, SystemClock.elapsedRealtime())?.let { first ->
            AppLogger.log(TAG, "Drive mode ${mode.word}: asked again within moments — answered from the first result")
            return SetDriveModeTool.alreadyDone(first)
        }
        report(TurnStatus.DispatchingCommand(AppLanguage.string(context, R.string.banner_drive_mode, mode.label)))
        val outcome = switch.switchTo(mode)
        driveModeRepeats.record(mode, outcome, SystemClock.elapsedRealtime())
        AppLogger.log(TAG, "Drive mode ${mode.word}: $outcome")
        return SetDriveModeTool.answer(outcome)
    }

    /** Adds a piece of what the user said to the exchange in progress (fragments arrive as they are recognised). */
    private fun noteUserWords(text: String) {
        if (text.isBlank()) return
        if (pendingUser.isNotEmpty() && !pendingUser.last().isWhitespace() && !text.first().isWhitespace()) pendingUser.append(' ')
        pendingUser.append(text)
    }

    /** The exchange in progress is complete: it is kept, briefly, so that a follow-up in the next conversation works. */
    private suspend fun recordExchange() {
        val user = pendingUser.toString()
        val actions = pendingActions.toList()
        val assistant = transcript.toString()
        pendingUser.clear()
        pendingActions.clear()
        if (!shortTermForSession) return
        try {
            memory.recordExchange(user, assistant, actions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.logError(TAG, "Could not keep the exchange", e)
        }
    }

    private suspend fun remember(call: GeminiFunctionCall, report: suspend (TurnStatus) -> Unit): JsonObject {
        if (!longTermForSession) return RememberTool.refused()
        val fact = RememberTool.fact(call) ?: return RememberTool.answer(AddOutcome.Empty)
        report(TurnStatus.DispatchingCommand(AppLanguage.string(context, R.string.banner_remember)))
        val outcome = memory.addFact(fact)
        if (outcome is AddOutcome.Added) factsForSession = factsForSession + outcome.fact
        return RememberTool.answer(outcome)
    }

    private suspend fun forgetMemory(call: GeminiFunctionCall, report: suspend (TurnStatus) -> Unit): JsonObject {
        if (!longTermForSession) return ForgetMemoryTool.refused()
        val match = ForgetMemoryTool.match(call) ?: return ForgetMemoryTool.answer(0)
        report(TurnStatus.DispatchingCommand(AppLanguage.string(context, R.string.banner_forget)))
        val removed = memory.forget(match)
        if (removed > 0) factsForSession = memory.currentFacts()
        return ForgetMemoryTool.answer(removed)
    }

    /** Gives up on a turn the server never answers. In a conversation that means listening again —
     * unless it keeps happening, which means something is wrong and listening forever won't fix it. */
    private fun armResponseWatchdog(firstWait: Boolean = false) {
        responseWatchdog?.cancel()
        responseWatchdog = scope.launch {
            val timeout = if (firstWait) FIRST_RESPONSE_TIMEOUT_MS.coerceAtMost(responseTimeoutMs) else responseTimeoutMs
            delay(timeout)
            AppLogger.log(TAG, "No server response ${timeout}ms after the user finished — giving up on this turn (sent $chunksThisTurn audio chunks)")
            when {
                endAfterReply -> endConversation()
                conversationActive && ++unansweredTurns < MAX_UNANSWERED_TURNS -> resumeAfterReply(reopenQuickly = true)
                else -> endConversation()
            }
        }
    }

    /** Tears down the session (WebSocket + mic). Safe to call even if nothing is active. Does NOT
     * stop audio already queued for playback — [LivePcmPlayer] keeps draining its buffer on its own
     * once queued, so closing here can't cut off the tail end of a reply that already arrived. */
    fun close() {
        conversationActive = false
        endAfterReply = false
        listenCapJob?.cancel()
        listenCapJob = null
        responseWatchdog?.cancel()
        responseWatchdog = null
        nextStepJob?.cancel()
        nextStepJob = null
        doneChimeJob?.cancel()
        doneChimeJob = null
        recorder.stop()
        gate = Gate.BLOCKED
        endDip()
        // What is being said outside the car is heard to the end before the speaker is switched back.
        if (player.speakingOutside) scope.launch { player.endOutside() }
        // A "play X" that was asked for during this conversation gets its last step now that nothing is listening.
        PlaybackFollowUp.startPending(context)
        holdJob?.cancel()
        holdJob = null
        utteranceHold.discard()
        synchronized(preRoll) { preRoll.clear() }
        eventJob?.cancel()
        eventJob = null
        liveClient.close()
    }

    /** What comes after a finished reply: wait for it to actually be heard, then listen again — or, if
     * the user asked to stop, say goodbye with the "done" tone and close. */
    private fun resumeAfterReply(reopenQuickly: Boolean = false) {
        nextStepJob?.cancel()
        nextStepJob = scope.launch {
            // The server streams audio faster than real time, so turn-complete arrives while most of
            // the reply is still queued to play.
            delay(player.remainingPlaybackMs() + if (reopenQuickly) 0 else REOPEN_DELAY_MS)
            if (!conversationActive) return@launch
            // An announcement made outside the car is over: wait for its tail, then the cabin has the next reply.
            player.endOutside()
            if (!conversationActive) return@launch

            if (endAfterReply) {
                endConversation()
                return@launch
            }
            if (!liveClient.isOpen && !reconnect()) {
                val offline = liveClient.lastSetupFailure == FailureKind.NO_NETWORK ||
                    (liveClient.lastSetupTimedOut && !NetworkState.isOnline(context))
                fail(
                    if (offline) UserMessages.text(context, FailureKind.NO_NETWORK) else AppLanguage.string(context, UserMessages.CONNECTION_LOST),
                    offline = offline,
                )
                return@launch
            }
            // Cheap when it is already running; keeps the idle timer from shutting it down mid-conversation.
            if (vehicleControlEnabledForSession) warmUpHelpers()
            transcript.clear()
            if (!recorder.isRecording && !recorder.start(onChunk = ::onMicChunk)) {
                fail(AppLanguage.string(context, R.string.err_mic_reopen))
                return@launch
            }
            openGate()
            onStatus?.invoke(TurnStatus.Listening)
        }
    }

    /** Ends the conversation because something broke. The status is reported from a fresh coroutine:
     * [close] cancels the one this usually runs in, which would swallow a report made after it. */
    private fun fail(message: String, offline: Boolean = false) {
        val report = onStatus
        earcons.play(if (offline) Earcon.OFFLINE else Earcon.ERROR)
        close()
        scope.launch { report?.invoke(TurnStatus.Failed(message)) }
    }

    /** The server closes an idle session after a while. The model loses the earlier turns when that
     * happens, but the conversation itself carries on. */
    private suspend fun reconnect(): Boolean {
        AppLogger.log(TAG, "The Live session was closed by the server — reconnecting")
        return openSession()
    }

    private suspend fun handleEvent(event: LiveEvent) {
        val report = onStatus ?: return
        // Which "generation" of playback this event belongs to: if the reply is cut off before its audio
        // is written, the write is dropped (see [LivePcmPlayer.currentEpoch]).
        val playbackEpoch = player.currentEpoch

        // The user cut a reply off locally: what is left of it in flight is dropped, until the server
        // acknowledges the interruption.
        if (discardReply) {
            when (event) {
                is LiveEvent.Audio, is LiveEvent.OutputTranscript -> return
                LiveEvent.Interrupted -> {
                    discardReply = false
                    releaseHeldSpeech("the server reported the interruption")
                }
                LiveEvent.TurnComplete -> {
                    // The reply the user cut off finished generating on the server; not the one that follows.
                    AppLogger.log(TAG, "Turn complete (of the reply the user cut off) — ignored")
                    discardReply = false
                    releaseHeldSpeech("the cut-off reply's turn completed")
                    return
                }
                else -> Unit
            }
        }

        // The server has started answering, so the user's part is over. Nothing of the mic goes up from
        // here until the reply is done or the user talks over it (see [Gate.BLOCKED]).
        if (gate != Gate.BLOCKED && (event is LiveEvent.Audio || event is LiveEvent.OutputTranscript || event is LiveEvent.ToolCall)) {
            AppLogger.log(TAG, "Server is answering — mic audio stays local (${endpointer.summary()})")
            listenCapJob?.cancel()
            gate = Gate.BLOCKED
            synchronized(preRoll) { preRoll.clear() }
        }
        // The first sound of a reply starts a fresh echo calibration.
        if ((event is LiveEvent.Audio || event is LiveEvent.OutputTranscript) && !replyAudioStarted) {
            bargeInDetector.startReply()
            endDip()
            replyAudioStarted = true
        }

        // Any server activity after the user finished proves it's still working on the turn.
        when (event) {
            LiveEvent.TurnComplete, is LiveEvent.Failed, LiveEvent.Closed -> responseWatchdog?.cancel()
            else -> if (!isListening) armResponseWatchdog()
        }
        if (event is LiveEvent.Audio || event is LiveEvent.OutputTranscript) {
            awaitingSpokenReply = false
            unansweredTurns = 0
            responseTimeoutMs = RESPONSE_TIMEOUT_MS
        }

        when (event) {
            is LiveEvent.Audio -> withContext(Dispatchers.IO) {
                player.playChunk(Base64.decode(event.pcmBase64, Base64.NO_WRAP), playbackEpoch)
            }
            is LiveEvent.OutputTranscript -> {
                transcript.append(event.text)
                report(TurnStatus.Speaking(transcript.toString().trim(), languageForSession))
            }
            is LiveEvent.InputTranscript -> {
                AppLogger.log(TAG, "heard: \"${event.text}\"")
                if (shortTermForSession) noteUserWords(event.text)
            }
            is LiveEvent.ToolCall -> {
                awaitingSpokenReply = true
                val responses = event.calls.map { call ->
                    AppLogger.log(TAG, "Tool call: ${call.name}(${call.args})")
                    if (shortTermForSession && call.name != RememberTool.FUNCTION_NAME && call.name != ForgetMemoryTool.FUNCTION_NAME) {
                        val action = RecentExchanges.describeCall(
                            call.name,
                            call.args.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: value.toString() },
                        )
                        // A model that repeats a call is not a longer list of things done.
                        if (pendingActions.lastOrNull() != action) pendingActions += action
                    }
                    val result = if (call.name == END_CONVERSATION_FUNCTION) {
                        // Ends once the goodbye the model is about to say has been heard.
                        endAfterReply = true
                        buildJsonObject { put("status", "ok") }
                    } else if (call.name == SpeakOutsideTool.FUNCTION_NAME) {
                        speakOutside(call, report)
                    } else if (call.name == PlaySoundOutsideTool.FUNCTION_NAME) {
                        playSoundOutside(call, report)
                    } else if (call.name == SetDriveModeTool.FUNCTION_NAME) {
                        setDriveMode(call, report)
                    } else if (call.name == RememberTool.FUNCTION_NAME) {
                        remember(call, report)
                    } else if (call.name == ForgetMemoryTool.FUNCTION_NAME) {
                        forgetMemory(call, report)
                    } else {
                        VehicleFunctionDispatcher.dispatch(call, vehicleControlEnabledForSession, vehicleController, context, cluster = clusterForSession) { displayName ->
                            report(TurnStatus.DispatchingCommand(displayName))
                        }.also { answer ->
                            // Music or a video is about to play through the speakers: the microphone would
                            // only hear it, so the conversation ends once the short confirmation is spoken.
                            if (call.name == PlayMediaTool.FUNCTION_NAME && (answer["status"] as? JsonPrimitive)?.content == "ok") {
                                endAfterReply = true
                            }
                        }
                    }
                    LiveFunctionResponse(id = call.id, name = call.name, response = result)
                }
                liveClient.sendToolResponses(responses)
            }
            LiveEvent.Interrupted -> {
                // The server heard the user over the reply (a barge-in it noticed itself, or ours reaching it).
                AppLogger.log(TAG, "Server reports the reply was interrupted")
                // What came before was cut short; the next exchange starts from what the user says now.
                pendingUser.clear()
                pendingActions.clear()
                interruptedAtMs = SystemClock.elapsedRealtime()
                player.stopAndFlush()
                transcript.clear()
            }
            LiveEvent.TurnComplete -> {
                if (SystemClock.elapsedRealtime() - interruptedAtMs < INTERRUPTED_TURN_COMPLETE_WINDOW_MS) {
                    // The end of the turn the user cut short, not of the reply that will follow.
                    AppLogger.log(TAG, "Turn complete (of the interrupted reply) — ignored")
                    interruptedAtMs = 0
                    return
                }
                if (awaitingSpokenReply) {
                    // The model handed over a function call; its spoken reply is still to come.
                    AppLogger.log(TAG, "Tool-call turn complete — waiting for the spoken reply")
                    armResponseWatchdog()
                    return
                }
                AppLogger.log(TAG, "Turn complete (${bargeInDetector.summary()})")
                // What the assistant said, for working out why it answered as it did. Debug builds only.
                if (BuildConfig.DEBUG && transcript.isNotBlank()) AppLogger.log(TAG, "said: \"${transcript.toString().trim()}\"")
                recordExchange()
                turnCompleted = true
                listenCapJob?.cancel()
                transcript.clear()
                if (conversationActive) resumeAfterReply()
            }
            // A failed connection ends the conversation — it used to leave the mic open and capturing
            // into a dead socket forever.
            is LiveEvent.Failed -> {
                AppLogger.logError(TAG, "Live session failed: ${event.reason} (${event.kind})")
                fail(UserMessages.text(context, event.kind), offline = event.kind == FailureKind.NO_NETWORK)
            }
            LiveEvent.Closed -> {
                if (conversationActive) {
                    // The server closed the session (they time out). Listening again re-opens it.
                    AppLogger.log(TAG, "Session closed by the server — the conversation continues on a new one")
                    listenCapJob?.cancel()
                    gate = Gate.BLOCKED
                    resumeAfterReply(reopenQuickly = true)
                } else {
                    report(TurnStatus.Done)
                }
            }
        }
    }
}

package com.bydassistantng.gemini

import android.content.Context
import com.bydassistantng.R
import com.bydassistantng.apps.AppLauncherTool
import com.bydassistantng.apps.CloseAppTool
import com.bydassistantng.audio.PlaySoundOutsideTool
import com.bydassistantng.audio.SoundEffect
import com.bydassistantng.audio.SpeakOutsideTool
import com.bydassistantng.memory.ForgetMemoryTool
import com.bydassistantng.memory.MemoryContext
import com.bydassistantng.memory.MemoryFacts
import com.bydassistantng.memory.RecentExchanges
import com.bydassistantng.memory.RememberTool
import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.media.MediaControlTool
import com.bydassistantng.media.PlayMediaTool
import com.bydassistantng.navigation.NavigationTool
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.LoggingVehicleController
import com.bydassistantng.vehicle.VolumeTool
import com.bydassistantng.passenger.PassengerScreenTool
import com.bydassistantng.vehicle.InAppVehicleController
import com.bydassistantng.vehicle.ReadCarTool
import com.bydassistantng.vehicle.needsCarLink
import com.bydassistantng.vehicle.SetDriveModeTool
import com.bydassistantng.cluster.ClusterOptions
import com.bydassistantng.cluster.ClusterScreenTool
import com.bydassistantng.vehicle.CommandAnswers
import com.bydassistantng.vehicle.CommandLookup
import com.bydassistantng.vehicle.VehicleCommandRegistry
import com.bydassistantng.vehicle.valuesText
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.VehicleQuery
import com.bydassistantng.vehicle.VehicleRepeatGuard
import com.bydassistantng.vehicle.labelFor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "VehicleDispatch"

/** The media action as the banner words it, in the app language (the model's own word if unknown). */
private fun mediaActionText(context: Context, action: String): String = when (action) {
    "play" -> AppLanguage.string(context, R.string.media_play)
    "pause" -> AppLanguage.string(context, R.string.media_pause)
    "next" -> AppLanguage.string(context, R.string.media_next)
    "previous" -> AppLanguage.string(context, R.string.media_previous)
    "stop" -> AppLanguage.string(context, R.string.media_stop)
    else -> action
}

/** The functions the model may call this turn. Navigation, opening apps and media keys are always offered
 * (they only open an app or press a media key); the vehicle commands only when the user has explicitly
 * armed vehicle control — without that, the model structurally cannot emit a call to one. With
 * [webSearch] the model may also look things up on Google (a Live API tool, answered by the server).
 * [exteriorSpeaker] offers speaking through the car's exterior speaker, in a conversation with vehicle
 * control armed and only in a build that has one; [soundEffects] are the ready-made sounds it can play.
 * [memoryTools] offers remembering and forgetting facts, in a conversation with long-term memory on.
 * [driveModes] offers changing the car's drive mode, like the exterior speaker only in a build that can.
 * [clusterScreen] offers putting an app on the instrument cluster (experimental: a car that has it, with its
 * switch on). */
fun assistantTools(
    vehicleControlEnabled: Boolean,
    conversation: Boolean = false,
    webSearch: Boolean = false,
    exteriorSpeaker: Boolean = false,
    soundEffects: List<SoundEffect> = emptyList(),
    memoryTools: Boolean = false,
    driveModes: Boolean = false,
    clusterScreen: Boolean = false,
): List<GeminiTool> {
    val declarations = buildList {
        add(NavigationTool.declaration)
        add(AppLauncherTool.declaration)
        add(CloseAppTool.declaration)
        add(MediaControlTool.declaration)
        add(PlayMediaTool.declaration)
        add(VolumeTool.declaration)
        if (conversation) add(endConversationDeclaration)
        // Only when the user has long-term memory switched on, in a conversation.
        if (conversation && memoryTools) {
            add(RememberTool.declaration)
            add(ForgetMemoryTool.declaration)
        }
        // The commands that are plain Android calls (the media volume) are offered whatever the switch says; the
        // ones that reach the car's own systems through the ADB helper only when vehicle control is armed.
        addAll(VehicleCommandRegistry.functionDeclarations(carLink = vehicleControlEnabled))
        if (vehicleControlEnabled) {
            add(ReadCarTool.declaration)
            add(PassengerScreenTool.declaration)
            // Needs a build that can switch the exterior speaker, and counts as controlling the car.
            if (conversation && exteriorSpeaker) {
                add(SpeakOutsideTool.declaration)
                if (soundEffects.isNotEmpty()) add(PlaySoundOutsideTool.declaration(soundEffects))
            }
            // Needs a build that can ask the car for a drive mode, and counts as controlling the car.
            if (conversation && driveModes) add(SetDriveModeTool.declaration)
            // An experimental feature with its Advanced settings switch on; it turns BYD Maps on and off by itself.
            if (conversation && clusterScreen) add(ClusterScreenTool.declaration)
        }
    }
    return buildList {
        add(GeminiTool(functionDeclarations = declarations))
        if (webSearch) add(GeminiTool(googleSearch = JsonObject(emptyMap())))
    }
}

/** Shared between [ConversationController] (classic request/response) and
 * [LiveConversationController] (WebSocket session) — both need to resolve a Gemini function call
 * and dispatch it identically; only how they get the call and send back the response differs by
 * protocol. Navigation is handled here; everything else is a
 * [com.bydassistantng.vehicle.VehicleCommand]. */
object VehicleFunctionDispatcher {
    // The model sometimes repeats a command it was told was accepted; the car gets it once (see VehicleRepeatGuard).
    private val repeatGuard = VehicleRepeatGuard()

    /** The user asked something new: asking for the same command again is on purpose, not a repeat. */
    fun newUserTurn() = repeatGuard.reset()

    suspend fun dispatch(
        functionCall: GeminiFunctionCall,
        vehicleControlEnabled: Boolean,
        reflectionController: VehicleController,
        context: Context,
        cluster: ClusterOptions = ClusterOptions.OFF,
        onDispatching: suspend (displayName: String) -> Unit,
    ): JsonObject {
        if (functionCall.name == NavigationTool.FUNCTION_NAME) {
            return NavigationTool.handle(context, functionCall, onDispatching)
        }

        if (functionCall.name == AppLauncherTool.FUNCTION_NAME) {
            // An app that is on the instrument cluster has its gauges put back before it is taken off.
            return AppLauncherTool.handle(
                context, functionCall, onDispatching,
                beforeLaunch = { pkg -> ClusterScreenTool.leaving(reflectionController, pkg) },
            )
        }
        if (functionCall.name == CloseAppTool.FUNCTION_NAME) {
            return CloseAppTool.handle(
                context, functionCall, onDispatching,
                closedElsewhere = { pkg -> ClusterScreenTool.closeIfOnCluster(reflectionController, pkg, cluster) },
            )
        }
        if (functionCall.name == PlayMediaTool.FUNCTION_NAME) {
            return PlayMediaTool.handle(context, functionCall, onDispatching)
        }
        if (functionCall.name == MediaControlTool.FUNCTION_NAME) {
            val action = (functionCall.args["action"] as? JsonPrimitive)?.content.orEmpty()
            onDispatching(AppLanguage.string(context, R.string.banner_media, mediaActionText(context, action)))
            return MediaControlTool.handle(context, functionCall)
        }

        if (functionCall.name == ReadCarTool.FUNCTION_NAME) {
            return readCar(functionCall, vehicleControlEnabled, reflectionController, context, onDispatching)
        }

        if (functionCall.name == PassengerScreenTool.FUNCTION_NAME) {
            return PassengerScreenTool.handle(
                context, functionCall, reflectionController, vehicleControlEnabled, onDispatching,
                beforeLaunch = { pkg -> ClusterScreenTool.leaving(reflectionController, pkg) },
            )
        }
        if (functionCall.name == ClusterScreenTool.FUNCTION_NAME) {
            return ClusterScreenTool.handle(context, functionCall, reflectionController, vehicleControlEnabled, cluster, onDispatching)
        }
        if (functionCall.name == VolumeTool.FUNCTION_NAME) {
            onDispatching(AppLanguage.string(context, R.string.banner_volume))
            return VolumeTool.read(context)
        }
        // A vehicle control is a family function (`seat`, `window`, …) naming one of its settings.
        val setting = (functionCall.args["setting"] as? JsonPrimitive)?.content
        val command = when (val lookup = VehicleCommandRegistry.resolve(functionCall.name, setting, vehicleControlEnabled)) {
            is CommandLookup.Found -> lookup.command
            CommandLookup.UnknownFunction -> return buildJsonObject { put("error", "Unknown command '${functionCall.name}'") }
            is CommandLookup.UnknownSetting -> return buildJsonObject {
                put("status", "error")
                put("error", "INVALID_ARGUMENT")
                val available = lookup.settings.joinToString(", ")
                put(
                    "detail",
                    when {
                        lookup.settings.isEmpty() -> "${functionCall.name} is not available."
                        setting.isNullOrBlank() -> "${functionCall.name} needs a setting: $available."
                        else -> "Unknown setting '$setting' for ${functionCall.name}. Settings: $available."
                    },
                )
            }
        }

        onDispatching(command.labelFor(AppLanguage.locale(context).language))

        val rawValue = (functionCall.args["value"] as? JsonPrimitive)?.content
        val resolvedValue = VehicleCommandRegistry.resolveValue(command, rawValue)
        val result = if (resolvedValue == null) {
            VehicleDispatchResult.Failure(
                VehicleDispatchError.INVALID_ARGUMENT,
                "Could not resolve value '$rawValue' for ${command.id}. Use: ${command.valuesText()}.",
            )
        } else {
            // A functionCall reaching here at all already implies vehicleControlEnabled was true
            // when tools were declared — this check is a second, independent gate against that
            // same fact, the same "two layers, not one" posture VehicleSafety itself documents,
            // rather than a currently-reachable branch.
            val controller = when {
                // A plain Android call: no ADB link and no vehicle-control switch involved.
                !command.needsCarLink -> InAppVehicleController(context)
                vehicleControlEnabled -> reflectionController
                else -> LoggingVehicleController
            }
            repeatGuard.dispatch(command.id, resolvedValue) {
                try {
                    controller.dispatch(command, resolvedValue)
                } catch (e: IllegalStateException) {
                    // VehicleSafety.assertDispatchAllowed rejecting a blocked domain — unreachable in
                    // practice since blocked domains are never in the registry Gemini was given
                    // functions for, but the hard gate stays authoritative over that assumption.
                    VehicleDispatchResult.Blocked(e.message ?: "blocked")
                }
            }
        }
        AppLogger.log(TAG, "dispatch ${command.id}(value=$rawValue -> $resolvedValue) -> $result")
        return result.toResponseJson()
    }

    /** Reading the car's sensors is gated by the same switch as control: the helper is the same ADB-shell power. */
    private suspend fun readCar(
        call: GeminiFunctionCall,
        vehicleControlEnabled: Boolean,
        controller: VehicleController,
        context: Context,
        onDispatching: suspend (String) -> Unit,
    ): JsonObject {
        val what = ReadCarTool.what(call)
            ?: return buildJsonObject {
                put("status", "error")
                put("error", "Unknown reading. Use one of: ${ReadCarTool.What.entries.joinToString { it.word }}.")
            }
        val banner = when (what) {
            ReadCarTool.What.TYRES -> R.string.banner_tyres
            ReadCarTool.What.OUTSIDE_TEMPERATURE -> R.string.banner_outside_temperature
            ReadCarTool.What.INSIDE_TEMPERATURE -> R.string.banner_inside_temperature
            ReadCarTool.What.AIR_QUALITY -> R.string.banner_air_quality
        }
        onDispatching(AppLanguage.string(context, banner))
        if (!vehicleControlEnabled) {
            return buildJsonObject {
                put("status", "error")
                put("error", "Vehicle features are switched off in the app's settings.")
            }
        }
        return when (val result = controller.query(what.query)) {
            is VehicleDispatchResult.Success -> {
                AppLogger.log(TAG, "read_car ${what.word} -> ${result.note}")
                ReadCarTool.report(what, result.note) ?: buildJsonObject {
                    put("status", "error")
                    put("error", "The car's answer could not be read.")
                }
            }
            else -> {
                AppLogger.log(TAG, "read_car ${what.word} -> $result")
                result.toResponseJson()
            }
        }
    }

    private fun VehicleDispatchResult.toResponseJson(): JsonObject = buildJsonObject {
        when (this@toResponseJson) {
            is VehicleDispatchResult.Success -> {
                put("status", "ok")
                CommandAnswers.forModel(note)?.let { put("note", it) }
            }
            is VehicleDispatchResult.Blocked -> {
                put("status", "blocked")
                put("reason", reason)
            }
            is VehicleDispatchResult.Failure -> {
                put("status", "error")
                put("error", error.name)
                detail?.let { put("detail", it) }
            }
        }
    }
}

/** Called by the model when the user says they're finished, to end a continuing conversation. */
const val END_CONVERSATION_FUNCTION = "end_conversation"

private val endConversationDeclaration = GeminiFunctionDeclaration(
    name = END_CONVERSATION_FUNCTION,
    description = "End the voice conversation: stop listening. Call this when the user says they are finished — " +
        "e.g. stop, that's all, thanks, goodbye, cancel — in English, Arabic or Russian.",
)

/** Shared system prompt for both the classic and Live conversation paths. [conversation] is the Live
 * path, where the assistant keeps listening after every reply until told to stop. [dialect] only matters
 * when the assistant answers in Arabic; [webSearch] says Google Search is available this session. */
fun voiceAssistantSystemPrompt(
    language: AssistantLanguage,
    conversation: Boolean = false,
    dialect: ArabicDialect = ArabicDialect.MATCH,
    webSearch: Boolean = false,
    memory: MemoryContext = MemoryContext(),
    musicHint: Boolean = false,
): String {
    // A fixed language is a firm rule and is said twice (here and again at the very end): a model that listens in many
    // languages, and was told only once, tends to answer in whichever it heard, which an unclear word can make the wrong one.
    val languageInstruction = when (language) {
        AssistantLanguage.AUTO -> "Reply in whichever language the user spoke — English, Arabic or Russian."
        AssistantLanguage.ENGLISH ->
            "Always reply in English. This is a firm rule: whatever language the user speaks, or seems to speak " +
                "(Arabic, Russian, Spanish, anything else), your reply is in English."
        AssistantLanguage.ARABIC ->
            "Always reply in Arabic. This is a firm rule: whatever language the user speaks, or seems to speak " +
                "(English, Russian, Spanish, anything else), your reply is in Arabic."
        AssistantLanguage.RUSSIAN ->
            "Always reply in Russian. This is a firm rule: whatever language the user speaks, or seems to speak " +
                "(English, Arabic, Spanish, anything else), your reply is in Russian."
    }
    val languageReminder = when (language) {
        AssistantLanguage.AUTO -> ""
        AssistantLanguage.ENGLISH ->
            " Remember: every reply is in English, whatever language you think you heard. If you could not make out " +
                "what was said, ask the user in English to say it again."
        AssistantLanguage.ARABIC ->
            " Remember: every reply is in Arabic, whatever language you think you heard. If you could not make out " +
                "what was said, ask the user in Arabic to say it again."
        AssistantLanguage.RUSSIAN ->
            " Remember: every reply is in Russian, whatever language you think you heard. If you could not make out " +
                "what was said, ask the user in Russian to say it again."
    }
    // Only said when it can matter: an assistant fixed to English or Russian never speaks Arabic.
    val dialectInstruction = dialect.promptName
        ?.takeIf { language == AssistantLanguage.AUTO || language == AssistantLanguage.ARABIC }
        ?.let { " When you reply in Arabic, speak $it — not another dialect." }
        .orEmpty()
    // The speech recognizer behind the Live API can't be told which language to expect (the native-audio
    // models choose it themselves), so say it here: what this user speaks, and what they mostly ask about,
    // so an unclear word is heard as the car command it most likely was.
    val listeningHint = " The user speaks Arabic (in any dialect), Russian and English, and sometimes mixes " +
        "them in one sentence. Most requests are about the car — windows, seats (heating, ventilation, massage), " +
        "the fridge, the A/C, lights, tyre pressure, navigation, music — so when a word is unclear, prefer the reading that is a " +
        "car or assistant request over an unrelated phrase."
    val searchInstruction = if (webSearch) {
        " For questions about current information — news, weather, sports results, prices, opening hours — " +
            "use Google Search, then answer in one or two spoken sentences without reading out links or sources."
    } else ""
    // Memory, when the user has it on: what it knows, how to add to it, and what was just said.
    val memoryInstruction = buildString {
        if (memory.tools) {
            append(
                " You have a memory. When the user asks you to remember something, call remember with one short " +
                    "sentence; when they ask you to forget something, call forget_memory. At most once in a " +
                    "conversation, and only for a clearly lasting preference or fact (never a one-off request), " +
                    "you may offer in one short sentence to remember it, and call remember only if they agree.",
            )
        }
        MemoryFacts.promptSection(memory.facts)?.let { append(" $it") }
        RecentExchanges.promptSection(memory.recent)?.let { append(" $it") }
    }
    // Opt-in: music with singing can reach the microphone, and the model takes sung words for someone speaking to it.
    val musicInstruction = if (musicHint) {
        " Music may be playing in the car. Sung words and other music are not the driver speaking to you: ignore them, and " +
            "answer only when a person speaks to you."
    } else ""
    return "You are a concise voice assistant built into a car's head unit. $languageInstruction$dialectInstruction " +
        "Keep replies short — they will be read aloud.$listeningHint When the user asks to be taken somewhere, call " +
        "navigate_to with the place name instead of describing the route. When the user asks to open an app, call " +
        "open_app, and to close or quit an app, call close_app; for play, pause, next or previous on the music, " +
        "call media_control. When the user asks you to " +
        "control something in the car and a matching function is available, call that function instead " +
        "of describing the action in words. If no matching function is available, say so plainly rather " +
        "than pretending to have done it.$searchInstruction$memoryInstruction$musicInstruction" +
        (
            if (conversation) {
                " This is an ongoing conversation: after you answer, the user may keep talking, so answer " +
                    "and wait. When the user says they are finished, call end_conversation and say a very short goodbye."
            } else {
                ""
            }
            ) +
        languageReminder
}

/**
 * Whether a spoken reply (as its transcript) is in the language the user fixed, as far as its letters say: a heuristic,
 * for the log only. Null when there is nothing to say (the language is automatic, or the reply has no letters).
 */
object ReplyLanguage {
    fun matches(language: AssistantLanguage, reply: String): Boolean? {
        val letters = reply.filter { it.isLetter() }
        if (letters.isEmpty()) return null
        val arabic = letters.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }
        val cyrillic = letters.count { it in '\u0400'..'\u052F' }
        return when (language) {
            AssistantLanguage.AUTO -> null
            // Names and a few English words inside an Arabic reply are fine; a reply with no Arabic at all is not.
            AssistantLanguage.ARABIC -> arabic > 0
            // Any Arabic, other scripts, or accented letters (Spanish, French...) are not English.
            AssistantLanguage.ENGLISH -> letters.all { it.code < 0x80 }
            // Names and a few English words inside a Russian reply are fine; a reply with no Cyrillic at all is not.
            AssistantLanguage.RUSSIAN -> cyrillic > 0
        }
    }
}

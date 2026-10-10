package com.bydassistantng.gemini

import android.content.Context
import com.bydassistantng.R
import com.bydassistantng.apps.AppLauncherTool
import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.media.MediaControlTool
import com.bydassistantng.media.PlayMediaTool
import com.bydassistantng.navigation.NavigationTool
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.LoggingVehicleController
import com.bydassistantng.vehicle.OutsideTemperatureTool
import com.bydassistantng.vehicle.TyreStatusTool
import com.bydassistantng.vehicle.VehicleCommandRegistry
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.VehicleQuery
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
 * [webSearch] the model may also look things up on Google (a Live API tool, answered by the server). */
fun assistantTools(vehicleControlEnabled: Boolean, conversation: Boolean = false, webSearch: Boolean = false): List<GeminiTool> {
    val declarations = buildList {
        add(NavigationTool.declaration)
        add(AppLauncherTool.declaration)
        add(MediaControlTool.declaration)
        add(PlayMediaTool.declaration)
        if (conversation) add(endConversationDeclaration)
        if (vehicleControlEnabled) {
            addAll(VehicleCommandRegistry.functionDeclarations())
            add(TyreStatusTool.declaration)
            add(OutsideTemperatureTool.declaration)
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
    suspend fun dispatch(
        functionCall: GeminiFunctionCall,
        vehicleControlEnabled: Boolean,
        reflectionController: VehicleController,
        context: Context,
        onDispatching: suspend (displayName: String) -> Unit,
    ): JsonObject {
        if (functionCall.name == NavigationTool.FUNCTION_NAME) {
            return NavigationTool.handle(context, functionCall, onDispatching)
        }

        if (functionCall.name == AppLauncherTool.FUNCTION_NAME) {
            return AppLauncherTool.handle(context, functionCall, onDispatching)
        }
        if (functionCall.name == PlayMediaTool.FUNCTION_NAME) {
            return PlayMediaTool.handle(context, functionCall, onDispatching)
        }
        if (functionCall.name == MediaControlTool.FUNCTION_NAME) {
            val action = (functionCall.args["action"] as? JsonPrimitive)?.content.orEmpty()
            onDispatching(AppLanguage.string(context, R.string.banner_media, mediaActionText(context, action)))
            return MediaControlTool.handle(context, functionCall)
        }

        if (functionCall.name == TyreStatusTool.FUNCTION_NAME) {
            onDispatching(AppLanguage.string(context, R.string.banner_tyres))
            return readTyres(vehicleControlEnabled, reflectionController)
        }
        if (functionCall.name == OutsideTemperatureTool.FUNCTION_NAME) {
            onDispatching(AppLanguage.string(context, R.string.banner_outside_temperature))
            return readOutsideTemperature(vehicleControlEnabled, reflectionController)
        }

        val command = VehicleCommandRegistry.byFunctionName(functionCall.name)
            ?: return buildJsonObject { put("error", "Unknown command '${functionCall.name}'") }

        onDispatching(command.labelFor(AppLanguage.isArabic(context)))

        val rawValue = (functionCall.args["value"] as? JsonPrimitive)?.content
        val resolvedValue = VehicleCommandRegistry.resolveValue(command, rawValue)
        val result = if (resolvedValue == null) {
            VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "Could not resolve value '$rawValue' for ${command.id}")
        } else {
            // A functionCall reaching here at all already implies vehicleControlEnabled was true
            // when tools were declared — this check is a second, independent gate against that
            // same fact, the same "two layers, not one" posture VehicleSafety itself documents,
            // rather than a currently-reachable branch.
            val controller = if (vehicleControlEnabled) reflectionController else LoggingVehicleController
            try {
                controller.dispatch(command, resolvedValue)
            } catch (e: IllegalStateException) {
                // VehicleSafety.assertDispatchAllowed rejecting a blocked domain — unreachable in
                // practice since blocked domains are never in the registry Gemini was given
                // functions for, but the hard gate stays authoritative over that assumption.
                VehicleDispatchResult.Blocked(e.message ?: "blocked")
            }
        }
        AppLogger.log(TAG, "dispatch ${command.id}(value=$rawValue -> $resolvedValue) -> $result")
        return result.toResponseJson()
    }

    /** Reading is gated by the same switch as control: the helper is the same ADB-shell power either way. */
    private suspend fun readTyres(vehicleControlEnabled: Boolean, controller: VehicleController): JsonObject {
        if (!vehicleControlEnabled) {
            return buildJsonObject {
                put("status", "error")
                put("error", "Vehicle features are switched off in the app's settings.")
            }
        }
        return when (val result = controller.query(VehicleQuery.TYRES)) {
            is VehicleDispatchResult.Success -> {
                val snapshot = result.note?.let { TyreStatusTool.parse(it) }
                AppLogger.log(TAG, "tyres -> ${result.note}")
                snapshot?.let { TyreStatusTool.report(it) } ?: buildJsonObject {
                    put("status", "error")
                    put("error", "The car's answer could not be read.")
                }
            }
            else -> {
                AppLogger.log(TAG, "tyres -> $result")
                result.toResponseJson()
            }
        }
    }

    private suspend fun readOutsideTemperature(vehicleControlEnabled: Boolean, controller: VehicleController): JsonObject {
        if (!vehicleControlEnabled) {
            return buildJsonObject {
                put("status", "error")
                put("error", "Vehicle features are switched off in the app's settings.")
            }
        }
        return when (val result = controller.query(VehicleQuery.OUTSIDE_TEMPERATURE)) {
            is VehicleDispatchResult.Success -> {
                AppLogger.log(TAG, "outside temperature -> ${result.note}")
                result.note?.let { OutsideTemperatureTool.parse(it) }?.let { OutsideTemperatureTool.report(it) } ?: buildJsonObject {
                    put("status", "error")
                    put("error", "The car's answer could not be read.")
                }
            }
            else -> {
                AppLogger.log(TAG, "outside temperature -> $result")
                result.toResponseJson()
            }
        }
    }

    private fun VehicleDispatchResult.toResponseJson(): JsonObject = buildJsonObject {
        when (this@toResponseJson) {
            is VehicleDispatchResult.Success -> {
                put("status", "ok")
                note?.let { put("note", it) }
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
        "e.g. stop, that's all, thanks, goodbye, cancel — in English or Arabic.",
)

/** Shared system prompt for both the classic and Live conversation paths. [conversation] is the Live
 * path, where the assistant keeps listening after every reply until told to stop. [dialect] only matters
 * when the assistant answers in Arabic; [webSearch] says Google Search is available this session. */
fun voiceAssistantSystemPrompt(
    language: AssistantLanguage,
    conversation: Boolean = false,
    dialect: ArabicDialect = ArabicDialect.MATCH,
    webSearch: Boolean = false,
): String {
    val languageInstruction = when (language) {
        AssistantLanguage.AUTO -> "Reply in whichever language the user spoke — English or Arabic."
        AssistantLanguage.ENGLISH -> "Reply in English, even if the user spoke Arabic."
        AssistantLanguage.ARABIC -> "Reply in Arabic, even if the user spoke English."
    }
    // Only said when it can matter: a fixed English assistant never speaks Arabic.
    val dialectInstruction = dialect.promptName
        ?.takeIf { language != AssistantLanguage.ENGLISH }
        ?.let { " When you reply in Arabic, speak $it — not another dialect." }
        .orEmpty()
    // The speech recognizer behind the Live API can't be told which language to expect (the native-audio
    // models choose it themselves), so say it here: what this user speaks, and what they mostly ask about,
    // so an unclear word is heard as the car command it most likely was.
    val listeningHint = " The user speaks Arabic (in any dialect) and English, and sometimes mixes " +
        "both in one sentence. Most requests are about the car — windows, seats (heating, ventilation, massage), " +
        "the fridge, the A/C, lights, tyre pressure, navigation, music — so when a word is unclear, prefer the reading that is a " +
        "car or assistant request over an unrelated phrase."
    val searchInstruction = if (webSearch) {
        " For questions about current information — news, weather, sports results, prices, opening hours — " +
            "use Google Search, then answer in one or two spoken sentences without reading out links or sources."
    } else ""
    return "You are a concise voice assistant built into a car's head unit. $languageInstruction$dialectInstruction " +
        "Keep replies short — they will be read aloud.$listeningHint When the user asks to be taken somewhere, call " +
        "navigate_to with the place name instead of describing the route. When the user asks to open an app, call " +
        "open_app; for play, pause, next or previous on the music, call media_control. When the user asks you to " +
        "control something in the car and a matching function is available, call that function instead " +
        "of describing the action in words. If no matching function is available, say so plainly rather " +
        "than pretending to have done it.$searchInstruction" +
        if (conversation) {
            " This is an ongoing conversation: after you answer, the user may keep talking, so answer " +
                "and wait. When the user says they are finished, call end_conversation and say a very short goodbye."
        } else ""
}

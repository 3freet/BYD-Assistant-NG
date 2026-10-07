package com.bydassistantng.media

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.util.AppLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "MediaControlTool"

/**
 * Play / pause / next / previous for whatever app is playing media, by sending it the same media key a
 * steering-wheel or headset button would. Nothing here can tell whether the app acted on it, so the answer
 * says "sent", not "done".
 */
object MediaControlTool {
    const val FUNCTION_NAME = "media_control"

    val keyCodes: Map<String, Int> = linkedMapOf(
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
    )

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Control the music or media that is playing: play, pause, skip to the next track, go back " +
            "to the previous one, or stop. Use for requests like \"pause the music\" or \"next song\". This does not " +
            "change the volume and cannot choose what to play — open the app for that.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "action" to GeminiSchema(type = "STRING", enum = keyCodes.keys.toList()),
            ),
            required = listOf("action"),
        ),
    )

    fun handle(context: Context, call: GeminiFunctionCall): JsonObject {
        val action = (call.args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
        val keyCode = keyCodes[action] ?: return buildJsonObject {
            put("status", "error")
            put("error", "Unknown media action \"$action\"")
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            AppLogger.log(TAG, "Sent media key for \"$action\"")
            buildJsonObject {
                put("status", "ok")
                put("note", "Sent \"$action\" to the media app; nothing confirms that it acted on it.")
            }
        } catch (e: Exception) {
            AppLogger.logError(TAG, "Could not send the media key for \"$action\"", e)
            buildJsonObject {
                put("status", "error")
                put("error", "Could not reach the media app")
            }
        }
    }
}

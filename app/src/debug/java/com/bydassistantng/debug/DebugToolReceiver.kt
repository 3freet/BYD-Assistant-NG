package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.apps.AppLauncherTool
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.media.MediaControlTool
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * `am broadcast -a com.bydassistantng.debug.TOOL -n <pkg>/com.bydassistantng.debug.DebugToolReceiver --es tool open_app --es args '{"name":"camera"}'`
 * runs one of the assistant's app tools exactly as the model's function call would, and logs what it answers
 * ("DebugTool: …" in the app log). Lets the tools be checked without a voice session.
 */
class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tool = intent.getStringExtra("tool") ?: return
        val args = runCatching { Json.parseToJsonElement(intent.getStringExtra("args") ?: "{}").jsonObject }.getOrNull() ?: return
        val call = GeminiFunctionCall(name = tool, args = args)
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val result = when (tool) {
                    AppLauncherTool.FUNCTION_NAME -> runBlocking { AppLauncherTool.handle(app, call) { } }
                    MediaControlTool.FUNCTION_NAME -> MediaControlTool.handle(app, call)
                    else -> null
                }
                AppLogger.log("DebugTool", "$tool($args) -> $result")
            } finally {
                pending.finish()
            }
        }.start()
    }
}

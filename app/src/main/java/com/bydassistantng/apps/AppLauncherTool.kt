package com.bydassistantng.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.R
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "AppLauncherTool"
private const val MAX_NAME_LENGTH = 100
// Enough for every app on the car (about 70 seen): a list cut off alphabetically would hide the later names from the model.
private const val MAX_LISTED_APPS = 200

/**
 * "Open the camera": starts an installed app on the car's screen. Like navigation it only opens an app and
 * never touches the car, so it is offered whether or not vehicle control is armed. If the name matches
 * nothing, the answer lists what IS installed, so the model can pick the closest name and try again —
 * the model, not this code, is what understands "the video app" or an Arabic name.
 */
object AppLauncherTool {
    const val FUNCTION_NAME = "open_app"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Open an installed app on the car's screen by name — music, radio, video, camera, " +
            "settings, any app. Use whenever the user asks to open, launch or switch to an app. If the app " +
            "is not found, the result lists the installed apps: pick the closest and call this again.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "name" to GeminiSchema(
                    type = "STRING",
                    description = "The app's name as it would appear on the car's screen, e.g. \"YouTube\" or \"Camera\".",
                ),
            ),
            required = listOf("name"),
        ),
    )

    suspend fun handle(context: Context, call: GeminiFunctionCall, onOpening: suspend (String) -> Unit): JsonObject {
        val name = (call.args["name"] as? JsonPrimitive)?.content?.trim().orEmpty().take(MAX_NAME_LENGTH)
        if (name.isEmpty()) return error("No app name was given")

        val installed = installedApps(context)
        return when (val match = AppMatcher.find(name, installed)) {
            is AppMatch.One -> {
                onOpening(AppLanguage.string(context, R.string.banner_opening_app, match.app.label))
                open(context, match.app)
            }
            is AppMatch.Several -> buildJsonObject {
                put("status", "ambiguous")
                put("note", "More than one app fits \"$name\" — ask the user which they mean.")
                put("candidates", JsonArray(match.apps.map { JsonPrimitive(it.label) }))
            }
            AppMatch.None -> buildJsonObject {
                put("status", "error")
                put("error", "No installed app matches \"$name\".")
                put("installedApps", JsonArray(installed.map { it.label }.distinct().sorted().take(MAX_LISTED_APPS).map { JsonPrimitive(it) }))
            }
        }
    }

    private fun installedApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    private fun open(context: Context, app: InstalledApp): JsonObject {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return error("${app.label} has no screen to open")
        return try {
            context.startActivity(intent)
            AppLogger.log(TAG, "Opened ${app.label} (${app.packageName})")
            buildJsonObject {
                put("status", "ok")
                put("note", "${app.label} was opened.")
            }
        } catch (e: ActivityNotFoundException) {
            AppLogger.logError(TAG, "${app.label} could not be opened", e)
            error("${app.label} could not be opened")
        } catch (e: SecurityException) {
            AppLogger.logError(TAG, "Not allowed to open ${app.label}", e)
            error("Not allowed to open ${app.label}")
        }
    }

    private fun error(message: String) = buildJsonObject {
        put("status", "error")
        put("error", message)
    }
}

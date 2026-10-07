package com.bydassistantng.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.R
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "NavigationTool"
private const val MAX_DESTINATION_LENGTH = 200

// Tried in this order. Google Maps is the usual maps handler on these head units; the others are what
// else may be installed that understands the same intent.
private val NAVIGATION_PACKAGES = listOf(
    "com.google.android.apps.maps" to "Google Maps",
    "net.osmand.plus" to "OsmAnd",
    "ru.yandex.yandexnavi" to "Yandex Navigator",
)

/**
 * "Take me to …": starts turn-by-turn navigation by handing the destination to the car's maps app.
 * Not a vehicle control, so it is offered to the model whether or not experimental vehicle control is
 * armed — it only opens an app, it never touches the car.
 */
object NavigationTool {
    const val FUNCTION_NAME = "navigate_to"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Start turn-by-turn navigation to a place in the car's maps app. Use whenever the user asks " +
            "to be taken, driven or navigated somewhere, or to start a route.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "destination" to GeminiSchema(
                    type = "STRING",
                    description = "The place to go to, as a name or address the maps app can search for, e.g. " +
                        "\"Central Station\" or \"City Library\". Use the name the user said; do not invent an address.",
                ),
            ),
            required = listOf("destination"),
        ),
    )

    /** @param onNavigating told the destination just before the maps app is opened, for status display. */
    suspend fun handle(context: Context, call: GeminiFunctionCall, onNavigating: suspend (String) -> Unit): JsonObject {
        val destination = (call.args["destination"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (destination.isEmpty()) return buildJsonObject {
            put("status", "error")
            put("error", "No destination was given")
        }
        val safeDestination = destination.take(MAX_DESTINATION_LENGTH)

        onNavigating(AppLanguage.string(context, R.string.banner_navigating, safeDestination))
        return start(context, safeDestination)
    }

    private fun start(context: Context, destination: String): JsonObject {
        val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")
        for ((packageName, appName) in NAVIGATION_PACKAGES) {
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                context.startActivity(intent)
                AppLogger.log(TAG, "Started navigation to \"$destination\" in $appName")
                return buildJsonObject {
                    put("status", "ok")
                    put("note", "Navigation to $destination started in $appName.")
                }
            } catch (e: ActivityNotFoundException) {
                AppLogger.logError(TAG, "$appName could not be opened for navigation", e)
            } catch (e: SecurityException) {
                AppLogger.logError(TAG, "Not allowed to open $appName", e)
            }
        }
        AppLogger.logError(TAG, "No navigation app could be opened for \"$destination\"")
        return buildJsonObject {
            put("status", "error")
            put("error", "No navigation app is available on this car")
        }
    }
}

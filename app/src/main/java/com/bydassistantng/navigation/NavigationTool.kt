package com.bydassistantng.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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

// Tried first, in this order. Google Maps is the usual maps handler on these head units; the others are what
// else may be installed that understands the same intent. Any other app that takes the link comes after these.
// The last two were added later and stay behind the first three, so a car that already navigates keeps its app.
private val KNOWN_NAVIGATION_APPS = listOf(
    "com.google.android.apps.maps" to "Google Maps",
    "net.osmand.plus" to "OsmAnd",
    "ru.yandex.yandexnavi" to "Yandex Navigator",
    // The Automotive build of Google Maps: on some head units the only maps app (issue #6).
    "com.google.android.apps.maps.automotive" to "Google Maps",
    // OsmAnd's free edition.
    "net.osmand" to "OsmAnd",
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
        val apps = appsThatNavigate(context.packageManager, Intent(Intent.ACTION_VIEW, uri))
        for (packageName in tryOrder(apps.keys)) {
            val appName = KNOWN_NAVIGATION_APPS.firstOrNull { it.first == packageName }?.second ?: apps.getValue(packageName)
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                context.startActivity(intent)
                AppLogger.log(TAG, "Started navigation to \"$destination\" in $appName ($packageName)")
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
        val found = apps.keys.joinToString().ifEmpty { "none" }
        AppLogger.logError(TAG, "No navigation app could be opened for \"$destination\" (apps that take a navigation link: $found)")
        return buildJsonObject {
            put("status", "error")
            put("error", "No navigation app is available on this car")
        }
    }

    /**
     * The order the apps that take a navigation link are tried in: the known ones in [KNOWN_NAVIGATION_APPS] order,
     * then every other one in the order the system listed them. Each app once.
     */
    internal fun tryOrder(apps: Collection<String>): List<String> {
        val known = KNOWN_NAVIGATION_APPS.map { it.first }
        return known.filter { it in apps } + apps.filter { it !in known }.distinct()
    }

    /**
     * Every app that can be handed the navigation link, by package, with the name it shows, in the order the system
     * lists them. The manifest's package-visibility queries let the app see them all; only activities that can be
     * started by such a link count.
     */
    private fun appsThatNavigate(pm: PackageManager, intent: Intent): Map<String, String> {
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        val apps = LinkedHashMap<String, String>()
        for (match in matches) {
            val packageName = match.activityInfo?.packageName ?: continue
            apps.getOrPut(packageName) { match.loadLabel(pm).toString().ifBlank { packageName } }
        }
        return apps
    }
}

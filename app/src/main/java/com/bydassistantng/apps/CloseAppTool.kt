package com.bydassistantng.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import com.bydassistantng.R
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.passenger.PassengerScreenParsing
import com.bydassistantng.util.AdbHelper
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "CloseAppTool"
private const val MAX_NAME_LENGTH = 100
private const val CONNECT_TIMEOUT_MS = 10_000L

/**
 * "Close YouTube": stops an app that is open on the car's screens. An ordinary app cannot stop another one, so it
 * is done over the loopback ADB connection the app already uses (`am force-stop`), which ends the app's playback
 * and everything it was doing. Like opening an app it does not touch the car, so it is offered whatever the
 * vehicle-control switch says. Only apps the user installed (or updated) are closed: the car's built-in apps, which
 * carry services the car relies on, and this app are never stopped from here.
 */
object CloseAppTool {
    const val FUNCTION_NAME = "close_app"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Close (stop) an app that is open on the car's screens, by name: \"close YouTube\", \"quit " +
            "Spotify\", \"shut the camera app\". The app stops completely and whatever it was playing ends. Only for " +
            "apps: not for the car's windows, doors, sunroof or boot, and not for taking an app off the instrument " +
            "cluster (use cluster_screen for that). If the app is not found, the result lists the installed apps: " +
            "pick the closest and call this again.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "name" to GeminiSchema(
                    type = "STRING",
                    description = "The app's name as it would appear on the car's screen, e.g. \"YouTube\".",
                ),
            ),
            required = listOf("name"),
        ),
    )

    /** An app the user installed, or a built-in one that was updated (so it came from a store). */
    internal fun isUserApp(flags: Int): Boolean =
        flags and ApplicationInfo.FLAG_SYSTEM == 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

    /** The car's own apps and this app are never closed from here; [userApp] says whether [packageName] is the user's. */
    internal fun mayClose(packageName: String, ownPackage: String, userApp: Boolean): Boolean =
        userApp &&
            packageName != ownPackage &&
            !packageName.startsWith("$ownPackage.") &&
            PassengerScreenParsing.isSafePackage(packageName) &&
            PassengerScreenParsing.mayClose(packageName)

    /**
     * [closedElsewhere] is told the package that is about to be stopped and may stop it itself, answering true (the
     * instrument cluster does so for the app on it, which also gives the cluster its gauges back).
     */
    suspend fun handle(
        context: Context,
        call: GeminiFunctionCall,
        onClosing: suspend (String) -> Unit,
        closedElsewhere: suspend (String) -> Boolean = { false },
    ): JsonObject {
        val name = (call.args["name"] as? JsonPrimitive)?.content?.trim().orEmpty().take(MAX_NAME_LENGTH)
        if (name.isEmpty()) return error("No app name was given")

        val installed = AppLauncherTool.installedApps(context)
        return when (val match = AppMatcher.find(name, installed)) {
            is AppMatch.One -> {
                val app = match.app
                val flags = runCatching { context.packageManager.getApplicationInfo(app.packageName, 0).flags }.getOrDefault(ApplicationInfo.FLAG_SYSTEM)
                if (!mayClose(app.packageName, context.packageName, isUserApp(flags))) {
                    return error("${app.label} is one of the car's built-in apps (or this assistant) and is not closed from here.")
                }
                onClosing(AppLanguage.string(context, R.string.banner_closing_app, app.label))
                if (closedElsewhere(app.packageName)) {
                    AppLogger.log(TAG, "Closed ${app.label} (${app.packageName}) on the instrument cluster")
                    buildJsonObject {
                        put("status", "ok")
                        put("note", "${app.label} was closed.")
                    }
                } else {
                    close(context, app)
                }
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

    private suspend fun close(context: Context, app: InstalledApp): JsonObject = withContext(Dispatchers.IO) {
        val adb = AdbHelper.connect(context, CONNECT_TIMEOUT_MS)
            ?: return@withContext error("Closing an app needs the car's ADB debugging, which could not be reached.")
        try {
            val wasRunning = adb.shell("pidof ${app.packageName}").output.isNotBlank()
            adb.shell("am force-stop ${app.packageName}")
            AppLogger.log(TAG, "Closed ${app.label} (${app.packageName}), it ${if (wasRunning) "was" else "was not"} running")
            buildJsonObject {
                put("status", "ok")
                put("note", if (wasRunning) "${app.label} was closed." else "${app.label} was not open.")
            }
        } catch (e: Throwable) {
            AppLogger.logError(TAG, "Could not close ${app.label}", e)
            error("${app.label} could not be closed.")
        } finally {
            runCatching { adb.close() }
        }
    }

    private fun error(message: String) = buildJsonObject {
        put("status", "error")
        put("error", message)
    }
}

// Enough for every app on the car (about 70 seen).
private const val MAX_LISTED_APPS = 200

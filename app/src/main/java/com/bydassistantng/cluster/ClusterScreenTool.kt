package com.bydassistantng.cluster

import android.content.Context
import android.content.Intent
import com.bydassistantng.R
import com.bydassistantng.apps.AppMatch
import com.bydassistantng.apps.AppMatcher
import com.bydassistantng.apps.InstalledApp
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.media.YouTubeLibrary
import com.bydassistantng.media.YouTubeVideoFinder
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "ClusterScreenTool"
private const val MAX_TEXT = 100
private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

/** What the assistant can do with the instrument cluster; the words are what the model sends. */
enum class ClusterAction(val word: String) {
    OPEN_APP("open_app"),
    CLOSE("close"),
    STATUS("status"),
    ;

    companion object {
        fun from(word: String?): ClusterAction? = entries.find { it.word.equals(word?.trim(), ignoreCase = true) }
    }
}

/**
 * "Show Yandex Navi on the instrument cluster", "take it off the cluster": puts an app on the driver's instrument
 * cluster and takes it off again, through the vehicle helper (see [ClusterScreenCommand]). A development feature:
 * it is offered only in a debug (development) build, with its switch on in Advanced settings.
 */
object ClusterScreenTool {
    const val FUNCTION_NAME = "cluster_screen"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Put an app on the car's instrument cluster (the driver's gauge display, also called the IPC or " +
            "the cluster) or take it off again. Actions: open_app shows an app there (set \"app\", for example " +
            "YouTube; an app that is already open on the main screen is moved there and keeps running; the gauges are " +
            "replaced by that app until it is closed), close STOPS the app on the cluster and gives the gauges " +
            "back (do not use it to move an app to the main screen: for that call open_app for the app, which " +
            "moves it and keeps it playing, and the gauges come back by themselves), status " +
            "says what is on it. For YouTube \"video\" names the video to play on the cluster, for example " +
            "\"open the Never Gonna Give You Up video on YouTube on the cluster\". Opening takes up to ten " +
            "seconds. Use only when the user asks for " +
            "the instrument cluster, the cluster, the IPC or the driver's gauge screen; for the main screen use " +
            "open_app and for the passenger screen use passenger_screen.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "action" to GeminiSchema(
                    type = "STRING",
                    description = "What to do with the instrument cluster.",
                    enum = ClusterAction.entries.map { it.word },
                ),
                "app" to GeminiSchema(
                    type = "STRING",
                    description = "For open_app: the app's name, e.g. \"Yandex Navi\".",
                ),
                "video" to GeminiSchema(
                    type = "STRING",
                    description = "For open_app with YouTube only: which video to play, as the words the user used " +
                        "(a title, a channel and topic) or a YouTube link. The best match starts playing on the " +
                        "cluster. Leave it out to open YouTube's home page.",
                ),
            ),
            required = listOf("action"),
        ),
    )

    /** The app this process last put on the cluster and has not taken off, or null. */
    @Volatile
    private var onCluster: String? = null

    /**
     * Called by the tools that are about to open [packageName] somewhere else: if that is the app on the cluster,
     * the cluster is told first, so that its gauges are back before the app is gone (and the map never shows).
     */
    suspend fun leaving(controller: VehicleController, packageName: String) {
        if (onCluster != packageName) return
        onCluster = null
        send(controller, "leaving $packageName")
    }

    /**
     * Called by the tool that is about to stop [packageName]: if that is the app on the cluster, the cluster is given
     * back the way `close` does it (gauges first, the app stopped, BYD Maps as chosen) and true is returned, so the
     * app does not have to be stopped again. False when the app is not on the cluster.
     */
    suspend fun closeIfOnCluster(controller: VehicleController, packageName: String, options: ClusterOptions): Boolean {
        if (onCluster != packageName) return false
        onCluster = null
        return send(controller, closeLine(options.keepMapsDisabled)) is VehicleDispatchResult.Success
    }

    suspend fun handle(
        context: Context,
        call: GeminiFunctionCall,
        controller: VehicleController,
        vehicleControlEnabled: Boolean,
        options: ClusterOptions,
        onDispatching: suspend (String) -> Unit,
    ): JsonObject {
        if (!vehicleControlEnabled) return error("Vehicle features are switched off in the app's settings.")
        if (!options.enabled) return error("The cluster screen is switched off in the app's Advanced settings.")
        val action = ClusterAction.from((call.args["action"] as? JsonPrimitive)?.content)
            ?: return error("Unknown action. Use one of: ${ClusterAction.entries.joinToString { it.word }}.")
        val app = (call.args["app"] as? JsonPrimitive)?.content?.trim().orEmpty().take(MAX_TEXT)
        val video = (call.args["video"] as? JsonPrimitive)?.content?.trim().orEmpty().take(MAX_TEXT)

        return when (action) {
            ClusterAction.OPEN_APP -> openApp(context, controller, app, video, policyWord(options.keepMapsDisabled), onDispatching)
            ClusterAction.CLOSE -> {
                onDispatching(AppLanguage.string(context, R.string.banner_cluster_close))
                onCluster = null
                answer(send(controller, closeLine(options.keepMapsDisabled)), ClusterAction.CLOSE)
            }
            ClusterAction.STATUS -> {
                onDispatching(AppLanguage.string(context, R.string.banner_cluster_status))
                answer(send(controller, "status"), ClusterAction.STATUS)
            }
        }
    }

    /** What is done with BYD Maps once the app has left the cluster: `disable` turns it off, `keep` leaves it enabled. */
    internal fun policyWord(keepMapsDisabled: Boolean) = if (keepMapsDisabled) "disable" else "keep"

    /** `close disable` turns BYD Maps off once the cluster has its gauges back, `close keep` leaves it enabled. */
    internal fun closeLine(keepMapsDisabled: Boolean) = if (keepMapsDisabled) "close disable" else "close keep"

    private suspend fun openApp(
        context: Context,
        controller: VehicleController,
        name: String,
        video: String,
        policy: String,
        onDispatching: suspend (String) -> Unit,
    ): JsonObject {
        if (name.isEmpty()) return error("No app name was given.")
        val installed = installedApps(context)
        return when (val match = AppMatcher.find(name, installed)) {
            is AppMatch.Several -> buildJsonObject {
                put("status", "ambiguous")
                put("note", "More than one app fits \"$name\" - ask the user which they mean.")
                put("candidates", JsonArray(match.apps.map { JsonPrimitive(it.label) }))
            }
            AppMatch.None -> error("No app matches \"$name\".")
            is AppMatch.One -> {
                onDispatching(AppLanguage.string(context, R.string.banner_cluster_open, match.app.label))
                if (video.isEmpty()) {
                    answer(send(controller, "open ${match.app.packageName} $policy"), ClusterAction.OPEN_APP, match.app.label)
                        .also { if (it["status"] == JsonPrimitive("ok")) onCluster = match.app.packageName }
                } else {
                    openVideo(controller, match.app, video, policy)
                }
            }
        }
    }

    /**
     * A video on the cluster: nobody can press a search result there, so the phrase is turned into a video first
     * (a pasted link is used as it is) and the app is opened on that video's own link.
     */
    private suspend fun openVideo(controller: VehicleController, app: InstalledApp, video: String, policy: String): JsonObject {
        if (app.packageName != YOUTUBE_PACKAGE) return error("A video can only be chosen for YouTube; ${app.label} was opened without one.")
        val linked = YouTubeLibrary.videoIdOf(video)
        val hit = if (linked != null) {
            YouTubeLibrary.VideoHit(linked, "")
        } else {
            YouTubeVideoFinder.find(video) ?: return error("No YouTube video was found for \"$video\".")
        }
        val result = send(controller, "open ${app.packageName} $policy ${YouTubeLibrary.watchUrl(hit.id)}")
        val answer = answer(result, ClusterAction.OPEN_APP, app.label)
        if (answer["status"] != JsonPrimitive("ok")) return answer
        onCluster = app.packageName
        return buildJsonObject {
            answer.forEach { (key, value) -> if (key != "note") put(key, value) }
            put(
                "note",
                "YouTube is now playing " + (hit.title.takeIf { it.isNotEmpty() }?.let { "\"$it\"" } ?: "that video") +
                    " on the instrument cluster, in place of the gauges.",
            )
        }
    }

    /** Sends one command to the helper and logs the exchange. */
    private suspend fun send(controller: VehicleController, line: String): VehicleDispatchResult =
        controller.clusterScreen(line).also { AppLogger.log(TAG, "$line -> $it") }

    internal fun answer(result: VehicleDispatchResult, action: ClusterAction, label: String? = null): JsonObject = when (result) {
        is VehicleDispatchResult.Success -> {
            val words = ClusterScreenParsing.words(result.note)
            buildJsonObject {
                put("status", "ok")
                when (action) {
                    ClusterAction.OPEN_APP -> put(
                        "note",
                        "${label ?: "The app"} is now shown on the instrument cluster, in place of the gauges" +
                            if (words.containsKey("moved")) " (it was moved there and keeps running)." else ".",
                    )
                    ClusterAction.CLOSE -> put("note", "The instrument cluster shows its gauges again.")
                    ClusterAction.STATUS -> {
                        val apps = words["apps"].orEmpty().split(',').filter { it.isNotEmpty() }
                        put(
                            "note",
                            if (apps.isEmpty()) "No app is on the instrument cluster." else "On the instrument cluster: ${apps.joinToString()}.",
                        )
                        put("top_package", words["top"].orEmpty())
                    }
                }
                put("instruction", "Done. Tell the user in a few words. Do not call this again for the same request.")
            }
        }
        is VehicleDispatchResult.Failure -> error(result.detail ?: "The instrument cluster did not respond.")
        is VehicleDispatchResult.Blocked -> error(result.reason)
    }

    private fun installedApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != ClusterScreenParsing.MAPS_PACKAGE }
            .distinctBy { it.packageName }
    }

    private fun error(message: String) = buildJsonObject {
        put("status", "error")
        put("error", message)
    }
}

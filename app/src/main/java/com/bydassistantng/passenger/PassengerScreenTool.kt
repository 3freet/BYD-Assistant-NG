package com.bydassistantng.passenger

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
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "PassengerScreenTool"
private const val MAX_TEXT = 100
private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

/** The Android user the passenger screen runs, whose apps are launched with `open 999 <package>`. */
private const val PASSENGER_USER = 999

/** What the passenger screen's apps are called when the user says "the video app" (they exist for its user only). */
internal data class PassengerOnlyApp(val label: String, val packageName: String, val words: List<List<String>>)

/** What the assistant can do on the passenger screen; the words are what the model sends. */
enum class PassengerAction(val word: String) {
    OPEN_APP("open_app"),
    CLOSE("close"),
    HOME("home"),
    BACK("back"),
    SCREEN_OFF("screen_off"),
    SCREEN_ON("screen_on"),
    STATUS("status"),
    ;

    companion object {
        fun from(word: String?): PassengerAction? = entries.find { it.word.equals(word?.trim(), ignoreCase = true) }
    }
}

/** Where an app named by the user lives: on the main user's side, on the passenger screen's own, or not found. */
internal sealed interface PassengerTarget {
    data class Main(val app: InstalledApp) : PassengerTarget
    data class PassengerOnly(val label: String, val packageName: String) : PassengerTarget
    data class Several(val labels: List<String>) : PassengerTarget
    data object None : PassengerTarget
}

/**
 * "Open YouTube on the passenger screen", "close it", "turn the passenger screen off": controls the car's second
 * (front-passenger) screen through the vehicle helper. It puts apps on that screen, stops them, sends home or
 * back to it and switches its backlight; it never touches the car's driving systems, so it follows the same
 * switch as vehicle control only because the helper is the ADB shell power either way.
 */
object PassengerScreenTool {
    const val FUNCTION_NAME = "passenger_screen"

    /** Apps the passenger screen has of its own (the main screen's list does not contain them). */
    internal val PASSENGER_ONLY_APPS = listOf(
        PassengerOnlyApp("Video player", "com.byd.videoplay.fse", listOf(listOf("video"), listOf("videos"), listOf("video", "player"), listOf("movies"))),
        PassengerOnlyApp("Youku", "com.byd.videoplay.youku.fse", listOf(listOf("youku"))),
        PassengerOnlyApp("Karaoke", "com.tencentbyd.karaokecar.fse", listOf(listOf("karaoke"), listOf("sing"), listOf("singing"))),
        PassengerOnlyApp("Photos", "com.byd.auto_photo.fse", listOf(listOf("photos"), listOf("photo"), listOf("gallery"), listOf("pictures"))),
        PassengerOnlyApp("Media center", "com.byd.mediacenter", listOf(listOf("media", "center"), listOf("media", "centre"), listOf("mediacenter"))),
        PassengerOnlyApp("Files", "com.byd.filemanager", listOf(listOf("files"), listOf("file", "manager"))),
        PassengerOnlyApp("App store", "com.byd.appstore", listOf(listOf("app", "store"), listOf("appstore"))),
        PassengerOnlyApp("Phone projection", "com.byd.screenproject", listOf(listOf("phone", "projection"), listOf("screen", "mirroring"), listOf("mirror"), listOf("cast"))),
        PassengerOnlyApp("Passenger settings", "com.byd.carsettings.fse", listOf(listOf("passenger", "settings"), listOf("settings"))),
        PassengerOnlyApp("Bluetooth", "com.byd.btsetting.fse", listOf(listOf("bluetooth"))),
    )

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Control the car's front-passenger screen (the second screen in front of the passenger; also " +
            "called the FSE or passenger display). Actions: open_app puts an app on it (set \"app\", for example " +
            "YouTube; for YouTube \"search\" opens the results for what to look for), close stops what is open on " +
            "it (everything, or the app named in \"app\"), home and back are its home and back buttons, " +
            "screen_off turns its backlight off and screen_on turns it back on, status says what is open. " +
            "Use whenever the user mentions the passenger screen, the front-passenger screen, the screen in front " +
            "of the passenger or the FSE. For the driver's own screen use open_app instead. Never use it just to try.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "action" to GeminiSchema(
                    type = "STRING",
                    description = "What to do on the passenger screen.",
                    enum = PassengerAction.entries.map { it.word },
                ),
                "app" to GeminiSchema(
                    type = "STRING",
                    description = "For open_app (and optionally close): the app's name, e.g. \"YouTube\".",
                ),
                "search" to GeminiSchema(
                    type = "STRING",
                    description = "For open_app with YouTube only: what to search for, so the results are shown.",
                ),
            ),
            required = listOf("action"),
        ),
    )

    /** Finds the app a spoken name refers to: the passenger screen's own apps first, then the main user's. */
    internal fun resolve(name: String, installed: List<InstalledApp>): PassengerTarget {
        val words = AppMatcher.normalize(name).split(' ').filter { it.isNotEmpty() && it !in FILLER }
        if (words.isEmpty()) return PassengerTarget.None
        PASSENGER_ONLY_APPS.firstOrNull { app -> app.words.any { it == words } }
            ?.let { return PassengerTarget.PassengerOnly(it.label, it.packageName) }
        return when (val match = AppMatcher.find(name, installed)) {
            is AppMatch.One -> PassengerTarget.Main(match.app)
            is AppMatch.Several -> PassengerTarget.Several(match.apps.map { it.label })
            AppMatch.None -> PassengerTarget.None
        }
    }

    private val FILLER = setOf("the", "app", "on", "passenger", "screen", "my", "a")

    suspend fun handle(
        context: Context,
        call: GeminiFunctionCall,
        controller: VehicleController,
        vehicleControlEnabled: Boolean,
        onDispatching: suspend (String) -> Unit,
    ): JsonObject {
        if (!vehicleControlEnabled) return error("Vehicle features are switched off in the app's settings.")
        val action = PassengerAction.from((call.args["action"] as? JsonPrimitive)?.content)
            ?: return error("Unknown action. Use one of: ${PassengerAction.entries.joinToString { it.word }}.")
        val app = (call.args["app"] as? JsonPrimitive)?.content?.trim().orEmpty().take(MAX_TEXT)
        val search = (call.args["search"] as? JsonPrimitive)?.content?.trim().orEmpty().take(MAX_TEXT)

        return when (action) {
            PassengerAction.OPEN_APP -> openApp(context, controller, app, search, onDispatching)
            PassengerAction.CLOSE -> {
                onDispatching(AppLanguage.string(context, R.string.banner_passenger_close))
                val target = if (app.isEmpty()) null else resolve(app, installedApps(context))
                val packageName = when (target) {
                    is PassengerTarget.Main -> target.app.packageName
                    is PassengerTarget.PassengerOnly -> target.packageName
                    else -> null
                }
                if (app.isNotEmpty() && packageName == null) {
                    error("No app matches \"$app\" on the passenger screen.")
                } else {
                    answer(send(controller, "close" + (packageName?.let { " $it" } ?: "")), AnswerKind.CLOSE)
                }
            }
            PassengerAction.HOME -> {
                onDispatching(AppLanguage.string(context, R.string.banner_passenger_home))
                answer(send(controller, "key home"), AnswerKind.PLAIN, "Sent the passenger screen to its home.")
            }
            PassengerAction.BACK -> {
                onDispatching(AppLanguage.string(context, R.string.banner_passenger_back))
                answer(send(controller, "key back"), AnswerKind.PLAIN, "Pressed back on the passenger screen.")
            }
            PassengerAction.SCREEN_OFF -> {
                onDispatching(AppLanguage.string(context, R.string.banner_passenger_screen_off))
                answer(send(controller, "screen off"), AnswerKind.PLAIN, "The passenger screen is turned off. Touching it or asking to turn it on brings it back.")
            }
            PassengerAction.SCREEN_ON -> {
                onDispatching(AppLanguage.string(context, R.string.banner_passenger_screen_on))
                answer(send(controller, "screen on"), AnswerKind.PLAIN, "The passenger screen is turned on.")
            }
            PassengerAction.STATUS -> {
                onDispatching(AppLanguage.string(context, R.string.banner_passenger_status))
                answer(send(controller, "status"), AnswerKind.STATUS)
            }
        }
    }

    private suspend fun openApp(
        context: Context,
        controller: VehicleController,
        name: String,
        search: String,
        onDispatching: suspend (String) -> Unit,
    ): JsonObject {
        if (name.isEmpty()) return error("No app name was given.")
        val installed = installedApps(context)
        return when (val target = resolve(name, installed)) {
            is PassengerTarget.Several -> buildJsonObject {
                put("status", "ambiguous")
                put("note", "More than one app fits \"$name\" - ask the user which they mean.")
                put("candidates", JsonArray(target.labels.map { JsonPrimitive(it) }))
            }
            PassengerTarget.None -> buildJsonObject {
                put("status", "error")
                put("error", "No app matches \"$name\" for the passenger screen.")
                put(
                    "available",
                    JsonArray(
                        (PASSENGER_ONLY_APPS.map { it.label } + installed.map { it.label }).distinct().sorted().take(80).map { JsonPrimitive(it) },
                    ),
                )
            }
            is PassengerTarget.PassengerOnly -> {
                onDispatching(AppLanguage.string(context, R.string.banner_passenger_open, target.label))
                answer(send(controller, "open $PASSENGER_USER ${target.packageName}"), AnswerKind.OPEN, label = target.label)
            }
            is PassengerTarget.Main -> {
                onDispatching(AppLanguage.string(context, R.string.banner_passenger_open, target.app.label))
                val link = if (search.isNotEmpty() && target.app.packageName == YOUTUBE_PACKAGE) YouTubeLibrary.searchUrl(search) else null
                val line = "open 0 ${target.app.packageName}" + (link?.let { " $it" } ?: "")
                answer(send(controller, line), AnswerKind.OPEN, label = target.app.label, searched = link != null)
            }
        }
    }

    /** Sends one command to the helper and logs the exchange. */
    private suspend fun send(controller: VehicleController, line: String): VehicleDispatchResult =
        controller.passengerScreen(line).also { AppLogger.log(TAG, "$line -> $it") }

    internal enum class AnswerKind { OPEN, CLOSE, STATUS, PLAIN }

    internal fun answer(
        result: VehicleDispatchResult,
        kind: AnswerKind,
        done: String? = null,
        label: String? = null,
        searched: Boolean = false,
    ): JsonObject {
        return when (result) {
            is VehicleDispatchResult.Success -> {
                val words = words(result.note)
                buildJsonObject {
                    put("status", "ok")
                    when (kind) {
                        AnswerKind.OPEN -> put(
                            "note",
                            "${label ?: "The app"} is now open on the passenger screen." +
                                if (searched) " It shows the search results; the passenger picks one." else "",
                        )
                        AnswerKind.CLOSE -> {
                            val closed = words["closed"].orEmpty().split(',').filter { it.isNotEmpty() }
                            put(
                                "note",
                                if (closed.isEmpty()) "Nothing was open on the passenger screen." else "Closed ${closed.size} app(s) on the passenger screen.",
                            )
                        }
                        AnswerKind.STATUS -> {
                            val top = words["top"].orEmpty()
                            val open = words["open"].orEmpty().split(',').filter { it.isNotEmpty() }
                            put(
                                "note",
                                if (open.isEmpty()) "The passenger screen shows its home screen; no app is open." else "Open on the passenger screen: ${open.joinToString()}.",
                            )
                            put("top_package", top)
                        }
                        AnswerKind.PLAIN -> put("note", done.orEmpty())
                    }
                    put("instruction", "Done. Tell the user in a few words. Do not call this again for the same request.")
                }
            }
            is VehicleDispatchResult.Failure -> error(result.detail ?: "The passenger screen did not respond.")
            is VehicleDispatchResult.Blocked -> error(result.reason)
        }
    }

    /** `key=value key=value` words of a helper answer. */
    internal fun words(note: String?): Map<String, String> =
        note.orEmpty().split(' ').mapNotNull { word ->
            word.substringBefore('=', "").takeIf { it.isNotEmpty() && '=' in word }?.let { it to word.substringAfter('=') }
        }.toMap()

    private fun installedApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    private fun error(message: String) = buildJsonObject {
        put("status", "error")
        put("error", message)
    }
}

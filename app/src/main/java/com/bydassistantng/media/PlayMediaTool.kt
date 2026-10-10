package com.bydassistantng.media

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import com.bydassistantng.R
import com.bydassistantng.apps.AppMatch
import com.bydassistantng.apps.AppMatcher
import com.bydassistantng.apps.InstalledApp
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "PlayMediaTool"
private const val MAX_TEXT_LENGTH = 200

/** What to play and where, as the model gave it. */
data class PlayRequest(val app: String, val query: String, val kind: MediaKind) {
    companion object {
        /** Null when there is nothing to search for or no app named. */
        fun from(call: GeminiFunctionCall): PlayRequest? {
            fun text(key: String) = (call.args[key] as? JsonPrimitive)?.content?.trim().orEmpty().take(MAX_TEXT_LENGTH)
            val app = text("app")
            val query = text("query")
            if (app.isEmpty() || query.isEmpty()) return null
            return PlayRequest(app, query, MediaKind.from(text("kind")))
        }
    }
}

/** What kind of thing the search phrase names; it tells the app what to look for. */
enum class MediaKind(val word: String, val focus: String) {
    ANY("any", "vnd.android.cursor.item/*"),
    SONG("song", "vnd.android.cursor.item/audio"),
    ARTIST("artist", "vnd.android.cursor.item/artist"),
    ALBUM("album", "vnd.android.cursor.item/album"),
    PLAYLIST("playlist", "vnd.android.cursor.item/playlist"),
    VIDEO("video", "vnd.android.cursor.item/video"),
    ;

    companion object {
        fun from(word: String): MediaKind = entries.find { it.word == word.trim().lowercase() } ?: ANY
    }
}

/**
 * "Play X on Spotify" / "play a video about Y on YouTube": Android's standard play-from-search request, which
 * an app that offers voice control answers by searching and starting the best match. No screen automation, no
 * account or API key. The apps that can do it are discovered on the device, so another music app that
 * supports the request works without a change here; the model, not this code, picks the app and the phrase
 * (including a song of its own choosing when the user leaves it open).
 *
 * It is not "press the first video on the home screen": with nothing to search for, nothing can be asked.
 */
object PlayMediaTool {
    const val FUNCTION_NAME = "play_media"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Start playing music or a video in an app on the car: a song, an artist, an album, a " +
            "playlist or a video, found by a search phrase. Use for \"play <song> on Spotify\", \"play a video " +
            "about <topic> on YouTube\", \"put on some music\". When the user leaves the choice to you " +
            "(\"something moody\", \"surprise me\"), pick one specific, well-known title and artist yourself " +
            "and pass them as the query. It cannot pick from an app's home screen: it always needs a search " +
            "phrase. It searches and starts the top result, which takes a few seconds. After it succeeds, " +
            "confirm in one short sentence; the conversation ends so the music does not get picked up by the " +
            "microphone.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "app" to GeminiSchema(
                    type = "STRING",
                    description = "The app to play it in, e.g. \"Spotify\" or \"YouTube\".",
                ),
                "query" to GeminiSchema(
                    type = "STRING",
                    description = "What to search for and play, e.g. \"Hotel California Eagles\" or \"how to change a tyre\".",
                ),
                "kind" to GeminiSchema(
                    type = "STRING",
                    description = "What the query names. Use \"any\" when unsure.",
                    enum = MediaKind.entries.map { it.word },
                ),
            ),
            required = listOf("app", "query"),
        ),
    )

    suspend fun handle(context: Context, call: GeminiFunctionCall, onPlaying: suspend (String) -> Unit): JsonObject {
        val request = PlayRequest.from(call) ?: return error("Both an app and something to search for are needed.")
        val players = players(context)
        if (players.isEmpty()) return error("No installed app can play from a search.")

        return when (val match = AppMatcher.find(request.app, players)) {
            is AppMatch.One -> {
                onPlaying(AppLanguage.string(context, R.string.banner_playing, request.query))
                play(context, match.app, request)
            }
            is AppMatch.Several -> buildJsonObject {
                put("status", "ambiguous")
                put("note", "More than one app fits \"${request.app}\" — ask the user which they mean.")
                put("candidates", JsonArray(match.apps.map { JsonPrimitive(it.label) }))
            }
            AppMatch.None -> buildJsonObject {
                put("status", "error")
                put("error", "\"${request.app}\" is not an installed app that can play from a search.")
                put("apps_that_can_play", JsonArray(players.map { JsonPrimitive(it.label) }.distinct()))
            }
        }
    }

    /** The installed apps that answer the play-from-search request. */
    private fun players(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val probe = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, 0)
        }
        return found.map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }.distinctBy { it.packageName }
    }

    private suspend fun play(context: Context, app: InstalledApp, request: PlayRequest): JsonObject {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(app.packageName)
            .putExtra(SearchManager.QUERY, request.query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, request.kind.focus)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppLogger.logError(TAG, "${app.label} could not play from a search", e)
            return error("${app.label} could not play that.")
        } catch (e: SecurityException) {
            AppLogger.logError(TAG, "Not allowed to start ${app.label}", e)
            return error("Not allowed to start ${app.label}.")
        }
        AppLogger.log(TAG, "Asked ${app.label} to play \"${request.query}\" (${request.kind.word})")

        // The apps answer the request by showing their results; the top result still has to be pressed.
        if (!FirstResult.hasRule(app.packageName)) {
            return partial("${app.label} is showing results for \"${request.query}\"; it cannot be started automatically. Tell the user to tap the first result.")
        }
        if (FirstResult.needsUiAutomation(app.packageName)) {
            // Reading this app's screen switches accessibility off for a while, so it waits until the conversation is over.
            PlaybackFollowUp.schedule(PendingPlayback(app.packageName, app.label, request.query, request.kind))
            return ok("${app.label} is opening results for \"${request.query}\" and will start the top one in a few seconds. Confirm in one short sentence.")
        }
        return when (val outcome = UiPlayStarter(context).startFirstResult(app.packageName, request.kind)) {
            is StartOutcome.Playing -> ok("${app.label} is now playing ${outcome.label.ifBlank { "\"${request.query}\"" }}.")
            is StartOutcome.Pressed -> ok("${app.label} was told to play ${outcome.label.ifBlank { "\"${request.query}\"" }} and starts in a moment.")
            is StartOutcome.PageOpened -> {
                // A playlist, album or artist opened its page; its play control is pressed once the conversation is over.
                PlaybackFollowUp.schedule(PendingPlayback(app.packageName, app.label, request.query, request.kind, pressPlayOnPage = true))
                ok("${app.label} opened \"${request.query}\" and will start it in a few seconds. Confirm in one short sentence.")
            }
            StartOutcome.NoResults -> partial("${app.label} opened but its results did not appear. Say you could not start it.")
            StartOutcome.NoRule -> partial("${app.label} is showing results for \"${request.query}\"; it cannot be started automatically. Tell the user to tap the first result.")
            StartOutcome.NoAdb -> partial("${app.label} is showing results for \"${request.query}\", but the car's debugging connection is not available to start it. Tell the user to tap the first result.")
        }
    }

    private fun ok(note: String) = buildJsonObject {
        put("status", "ok")
        put("note", note)
    }

    private fun partial(note: String) = buildJsonObject {
        put("status", "partial")
        put("note", note)
    }

    private fun error(message: String) = buildJsonObject {
        put("status", "error")
        put("error", message)
    }
}

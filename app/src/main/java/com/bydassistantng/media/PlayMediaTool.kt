package com.bydassistantng.media

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

/**
 * What to play and where, as the model gave it. The [query] is what to search for; for the user's own library
 * it is optional (the name of one playlist, when one was named).
 */
data class PlayRequest(val app: String, val query: String, val kind: MediaKind, val source: PlaySource = PlaySource.SEARCH) {
    companion object {
        /** Null when there is no app named, or nothing to search for when a search is what was asked. */
        fun from(call: GeminiFunctionCall): PlayRequest? {
            fun text(key: String) = (call.args[key] as? JsonPrimitive)?.content?.trim().orEmpty().take(MAX_TEXT_LENGTH)
            val app = text("app")
            val query = text("query")
            val source = PlaySource.from(text("source"))
            if (app.isEmpty()) return null
            if (query.isEmpty() && source == PlaySource.SEARCH) return null
            return PlayRequest(app, query, MediaKind.from(text("kind")), source)
        }
    }
}

/** Where the music comes from: anything in the catalogue (a search), or the user's own library. */
enum class PlaySource(val word: String) {
    SEARCH("search"),
    FAVORITES("favorites"),
    MY_PLAYLISTS("my_playlists"),
    ;

    companion object {
        /** What a model that does not follow the list exactly tends to say instead. */
        private val ALIASES = mapOf(
            "favourites" to FAVORITES,
            "favorite" to FAVORITES,
            "favourite" to FAVORITES,
            "liked" to FAVORITES,
            "liked_songs" to FAVORITES,
            "playlists" to MY_PLAYLISTS,
            "playlist" to MY_PLAYLISTS,
            "my_playlist" to MY_PLAYLISTS,
        )

        fun from(word: String): PlaySource {
            val key = word.trim().lowercase().replace(' ', '_').replace('-', '_')
            return entries.find { it.word == key } ?: ALIASES[key] ?: SEARCH
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
 * It is not "press the first video on the home screen": with nothing to search for, nothing can be asked. The
 * one exception is the user's own Spotify or YouTube library ("my favorites", "my playlists"), which has links of
 * its own.
 */
object PlayMediaTool {
    const val FUNCTION_NAME = "play_media"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Start playing music or a video in an app on the car: a song, an artist, an album, a " +
            "playlist or a video, found by a search phrase. Use for \"play <song> on Spotify\", \"play a video " +
            "about <topic> on YouTube\", \"put on some music\". When the user leaves the choice to you " +
            "(\"something moody\", \"surprise me\"), pick one specific, well-known title and artist yourself " +
            "and pass them as the query. It cannot pick from an app's home screen: a search always needs a " +
            "search phrase. It searches and starts the top result, which takes a few seconds. " +
            "For the user's own library in Spotify or YouTube there is no search phrase: \"play my favorites\" / " +
            "\"my liked songs\" / \"my liked videos\" is source favorites, and \"play music from my " +
            "playlists\" / \"play my watch later\" is source my_playlists (put the playlist's name in query " +
            "only when the user said one, e.g. \"play my road trip playlist\"; otherwise leave query out and " +
            "their most recent playlist plays). After it " +
            "succeeds, confirm in one short sentence; the conversation ends so the music does not get picked " +
            "up by the microphone.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "app" to GeminiSchema(
                    type = "STRING",
                    description = "The app to play it in, e.g. \"Spotify\" or \"YouTube\".",
                ),
                "query" to GeminiSchema(
                    type = "STRING",
                    description = "What to search for and play, e.g. \"Hotel California Eagles\" or \"how to change a tyre\". " +
                        "Required for a search. For source my_playlists it is the playlist's name, if one was said; " +
                        "leave it out for favorites.",
                ),
                "kind" to GeminiSchema(
                    type = "STRING",
                    description = "What the query names. Use \"any\" when unsure.",
                    enum = MediaKind.entries.map { it.word },
                ),
                "source" to GeminiSchema(
                    type = "STRING",
                    description = "Where to play from. \"search\" (default): search for the query. \"favorites\": the " +
                        "user's liked songs (liked videos on YouTube). \"my_playlists\": one of the user's own " +
                        "playlists. Only Spotify and YouTube support favorites and my_playlists.",
                    enum = PlaySource.entries.map { it.word },
                ),
            ),
            required = listOf("app"),
        ),
    )

    suspend fun handle(context: Context, call: GeminiFunctionCall, onPlaying: suspend (String) -> Unit): JsonObject {
        val request = PlayRequest.from(call)
            ?: return error("An app is needed, and something to search for (unless it is the user's own favorites or playlists).")
        val players = players(context)
        if (players.isEmpty()) return error("No installed app can play from a search.")

        return when (val match = AppMatcher.find(request.app, players)) {
            is AppMatch.One -> {
                onPlaying(banner(context, request))
                if (request.source == PlaySource.SEARCH) play(context, match.app, request) else playFromLibrary(context, match.app, request)
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

    /** What the status banner says while it starts. */
    private fun banner(context: Context, request: PlayRequest): String = when {
        request.source == PlaySource.FAVORITES -> AppLanguage.string(context, R.string.banner_playing_favorites)
        request.source == PlaySource.MY_PLAYLISTS && request.query.isBlank() -> AppLanguage.string(context, R.string.banner_playing_playlists)
        else -> AppLanguage.string(context, R.string.banner_playing, request.query)
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
        val intent = searchIntent(app, request)
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
        return when (val outcome = UiPlayStarter(context).startFirstResult(app.packageName, request.kind, request.query)) {
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

    /**
     * "Play my favorites" / "play music from my playlists": the app's own page for that part of the library is
     * opened through its link, and the play control of what opens is pressed once the conversation is over (see
     * [PlaybackFollowUp]). Nothing is searched, so there is no phrase and no ranking to get wrong.
     */
    private fun playFromLibrary(context: Context, app: InstalledApp, request: PlayRequest): JsonObject {
        val link = when (app.packageName) {
            FirstResult.SPOTIFY -> SpotifyLibrary.linkFor(app.packageName, request.source)
            FirstResult.YOUTUBE -> YouTubeLibrary.linkFor(request.source)
            else -> null
        } ?: return partial("${app.label} cannot play from the user's own library by voice, only Spotify and YouTube can. Offer to search for something to play instead.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(app.packageName)
        if (app.packageName == FirstResult.YOUTUBE) freshTask(intent) else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppLogger.logError(TAG, "${app.label} could not open $link", e)
            return error("${app.label} could not open that.")
        } catch (e: SecurityException) {
            AppLogger.logError(TAG, "Not allowed to start ${app.label}", e)
            return error("Not allowed to start ${app.label}.")
        }
        AppLogger.log(TAG, "Asked ${app.label} to open ${request.source.word}${request.query.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()}")
        PlaybackFollowUp.schedule(PendingPlayback(app.packageName, app.label, request.query, MediaKind.PLAYLIST, source = request.source))
        val what = when {
            request.source == PlaySource.FAVORITES -> if (app.packageName == FirstResult.YOUTUBE) "the user's liked videos" else "the user's liked songs"
            request.query.isNotBlank() -> "the user's playlist \"${request.query}\""
            else -> "the user's most recent playlist"
        }
        return ok("${app.label} is opening the library and will start $what in a few seconds. Confirm in one short sentence.")
    }

    /**
     * The request that makes [app] show results for the phrase. Android's play-from-search request, except for
     * YouTube, which ignores it while it is already open (the page of the last search stays, and the first result
     * of that would be pressed); its own search link is answered in any state.
     */
    private fun searchIntent(app: InstalledApp, request: PlayRequest): Intent =
        if (app.packageName == FirstResult.YOUTUBE) {
            freshTask(Intent(Intent.ACTION_VIEW, Uri.parse(YouTubeLibrary.searchUrl(request.query))).setPackage(app.packageName))
        } else {
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(app.packageName)
                .putExtra(SearchManager.QUERY, request.query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, request.kind.focus)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * YouTube starts in a task of its own: whatever was open (a video playing, the page of an earlier search) is
     * closed, so the page that follows is the only thing on screen. A video left playing keeps the screen busy
     * and a busy screen cannot be read, and the first result of an old page must not be what gets pressed.
     */
    private fun freshTask(intent: Intent): Intent =
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

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

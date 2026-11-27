package com.bydassistantng.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.gemini.SettingsPaths
import com.bydassistantng.R
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

private const val TAG = "NavigationTool"
private const val MAX_DESTINATION_LENGTH = 200

// Tried first, in this order, when no app is chosen in Settings. Google Maps is the usual maps handler on these head units;
// the others are what else may be installed that understands the same intent. Any other app that takes the link comes
// after these. The last two were added later and stay behind the first three, so a car that already navigates keeps its app.
// Each has a name of its own, which the assistant says and Settings shows, so that two builds of one app can be told apart.
private val KNOWN_NAVIGATION_APPS = listOf(
    "com.google.android.apps.maps" to "Google Maps",
    "net.osmand.plus" to "OsmAnd+",
    "ru.yandex.yandexnavi" to "Yandex Navigator",
    // The Automotive build of Google Maps: on some head units the only maps app (issue #6).
    "com.google.android.apps.maps.automotive" to "Google Maps Automotive",
    // OsmAnd's free edition.
    "net.osmand" to "OsmAnd",
)

/** A maps app on the car that can be handed the navigation link, with the name the assistant says and Settings shows. */
data class NavigationApp(val packageName: String, val name: String)

/** How the tool reads the app chosen in Settings (it is not created by Hilt). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NavigationEntryPoint {
    fun preferences(): PreferencesRepository
}

/**
 * "Take me to …": starts turn-by-turn navigation by handing the destination to the car's maps app: the one chosen in
 * Settings, or the first one found. Not a vehicle control, so it is offered to the model whether or not experimental
 * vehicle control is armed — it only opens an app, it never touches the car.
 */
object NavigationTool {
    const val FUNCTION_NAME = "navigate_to"
    private const val DESTINATION = "destination"
    private const val SAVED_PLACE = "saved_place"

    private const val DESCRIPTION = "Start turn-by-turn navigation to a place in the car's maps app. Use whenever the user " +
        "asks to be taken, driven or navigated somewhere, or to start a route."
    private const val DESTINATION_DESCRIPTION = "The place to go to, as a name or address the maps app can search for, e.g. " +
        "\"Central Station\" or \"City Library\". Use the name the user said; do not invent an address."

    /** The declaration when the user has no favorite places. */
    val declaration = declarationFor(emptyList())

    /**
     * The declaration for a session: with [favoritePlaces] (their names), the model may name one of them instead of a
     * destination, and can only name one that exists. Their addresses are never in it.
     */
    fun declarationFor(favoritePlaces: List<String>): GeminiFunctionDeclaration {
        val names = favoritePlaces.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (names.isEmpty()) {
            return GeminiFunctionDeclaration(
                name = FUNCTION_NAME,
                description = DESCRIPTION,
                parameters = GeminiSchema(
                    type = "OBJECT",
                    properties = mapOf(DESTINATION to GeminiSchema(type = "STRING", description = DESTINATION_DESCRIPTION)),
                    required = listOf(DESTINATION),
                ),
            )
        }
        return GeminiFunctionDeclaration(
            name = FUNCTION_NAME,
            description = "$DESCRIPTION To go to one of the user's favorite places, give $SAVED_PLACE instead of a destination.",
            parameters = GeminiSchema(
                type = "OBJECT",
                properties = mapOf(
                    DESTINATION to GeminiSchema(type = "STRING", description = "$DESTINATION_DESCRIPTION Leave it out when $SAVED_PLACE is given."),
                    SAVED_PLACE to GeminiSchema(
                        type = "STRING",
                        enum = names,
                        description = "One of the user's favorite places, by its name. Use it when the user asks to go to " +
                            "it, by this name or in other words or another language (\"take me home\" is Home). Its address " +
                            "is kept on the car: never ask for it or say it.",
                    ),
                ),
            ),
        )
    }

    /** @param onNavigating told the destination just before the maps app is opened, for status display. */
    suspend fun handle(context: Context, call: GeminiFunctionCall, onNavigating: suspend (String) -> Unit): JsonObject {
        val savedPlace = (call.args[SAVED_PLACE] as? JsonPrimitive)?.content?.trim().orEmpty()
        val destination = (call.args[DESTINATION] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (savedPlace.isEmpty() && destination.isEmpty()) return buildJsonObject {
            put("status", "error")
            put("error", "No destination was given")
        }
        val target = resolve(favoritePlaces(context), savedPlace, destination) ?: return buildJsonObject {
            put("status", "error")
            put(
                "error",
                "There is no favorite place called \"$savedPlace\". The user can save one by asking you to save where the car " +
                    "is now under that name, or in ${SettingsPaths.favoritePlaces(context)}.",
            )
        }

        onNavigating(AppLanguage.string(context, R.string.banner_navigating, target.label))
        return start(context, target, chosenApp(context))
    }

    /** Where to go, with what to call it: a favorite place's name stands in for its destination everywhere but the link. */
    internal data class Target(val label: String, val destination: String, val favorite: Boolean)

    /**
     * Where to go: the favorite place named [savedPlace]; else the one whose name is exactly the [destination] said (a
     * place saved during this conversation is not among the session's names yet); else the [destination] as said. Null when
     * a favorite place was named that does not exist and nothing else was given.
     */
    internal fun resolve(places: List<FavoritePlace>, savedPlace: String, destination: String): Target? {
        FavoritePlaces.find(places, savedPlace)?.let { return Target(it.name, it.destination, favorite = true) }
        FavoritePlaces.find(places, destination)?.let { return Target(it.name, it.destination, favorite = true) }
        if (destination.isEmpty()) return null
        val asSaid = destination.take(MAX_DESTINATION_LENGTH)
        return Target(asSaid, asSaid, favorite = false)
    }

    /** The favorite places on the car; none when they cannot be read. */
    private suspend fun favoritePlaces(context: Context): List<FavoritePlace> = try {
        EntryPointAccessors.fromApplication(context.applicationContext, NavigationEntryPoint::class.java)
            .preferences().favoritePlaces.first()
    } catch (e: IOException) {
        AppLogger.logError(TAG, "Could not read the favorite places", e)
        emptyList()
    }

    /** The app chosen in Settings, by package; empty when none is (the first one found is used). */
    private suspend fun chosenApp(context: Context): String = try {
        EntryPointAccessors.fromApplication(context.applicationContext, NavigationEntryPoint::class.java)
            .preferences().navigationApp.first()
    } catch (e: IOException) {
        AppLogger.logError(TAG, "Could not read the navigation app chosen in Settings; using the first one found", e)
        ""
    }

    private fun start(context: Context, target: Target, chosen: String): JsonObject {
        val uri = navigationLink(target.destination)
        // What the log and the assistant are told: a favorite place by its name only, never its address.
        val destination = target.label
        val what = if (target.favorite) "the favorite place \"$destination\"" else "\"$destination\""
        val apps = appsThatNavigate(context.packageManager, Intent(Intent.ACTION_VIEW, uri))
        if (chosen.isNotEmpty() && chosen !in apps) {
            AppLogger.log(TAG, "The navigation app chosen in Settings ($chosen) is not on the car or does not take the link; trying the others")
        }
        for (packageName in tryOrder(apps.keys, chosen)) {
            val appName = nameOf(packageName, apps[packageName])
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            try {
                context.startActivity(intent)
                val how = if (packageName == chosen) ", chosen in Settings" else ""
                AppLogger.log(TAG, "Started navigation to $what in $appName ($packageName)$how")
                return buildJsonObject {
                    put("status", "ok")
                    // When the app chosen in Settings was passed over, the assistant can say so.
                    val passedOver = if (chosen.isNotEmpty() && packageName != chosen) {
                        " The navigation app chosen in Settings, ${nameOf(context, chosen)}, could not be opened."
                    } else {
                        ""
                    }
                    put("note", "Navigation to $destination started in $appName.$passedOver")
                }
            } catch (e: ActivityNotFoundException) {
                AppLogger.logError(TAG, "$appName could not be opened for navigation", e)
            } catch (e: SecurityException) {
                AppLogger.logError(TAG, "Not allowed to open $appName", e)
            }
        }
        val found = apps.keys.joinToString().ifEmpty { "none" }
        AppLogger.logError(TAG, "No navigation app could be opened for $what (apps that take a navigation link: $found)")
        return buildJsonObject {
            put("status", "error")
            put("error", "No navigation app is available on this car")
        }
    }

    /**
     * The order the apps that take a navigation link are tried in: the [chosen] one first when it is among them, then the
     * known ones in [KNOWN_NAVIGATION_APPS] order, then every other one in the order the system listed them. Each app once.
     */
    internal fun tryOrder(apps: Collection<String>, chosen: String = ""): List<String> {
        val known = KNOWN_NAVIGATION_APPS.map { it.first }
        val automatic = known.filter { it in apps } + apps.filter { it !in known }.distinct()
        return if (chosen in automatic) listOf(chosen) + automatic.filter { it != chosen } else automatic
    }

    /** The apps on this car that can take the navigation link, in the order they are tried when none is chosen: for Settings. */
    fun available(context: Context): List<NavigationApp> {
        val apps = appsThatNavigate(context.packageManager, Intent(Intent.ACTION_VIEW, navigationLink("")))
        return tryOrder(apps.keys).map { NavigationApp(it, nameOf(it, apps[it])) }
    }

    /** The name Settings shows for the app [packageName], also when the car no longer has it (then its package, unless known). */
    fun nameOf(context: Context, packageName: String): String {
        knownName(packageName)?.let { return it }
        val label = try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return nameOf(packageName, label)
    }

    /** The name of each app is its own in [KNOWN_NAVIGATION_APPS], otherwise the one it shows ([label]), otherwise its package. */
    internal fun nameOf(packageName: String, label: String?): String =
        knownName(packageName) ?: label?.takeIf { it.isNotBlank() } ?: packageName

    private fun knownName(packageName: String): String? = KNOWN_NAVIGATION_APPS.firstOrNull { it.first == packageName }?.second

    /** The link the maps apps take; coordinates go in as they are ("23.588000,58.382898"), anything else is encoded. */
    private fun navigationLink(destination: String): Uri {
        val query = if (FavoritePlaces.isCoordinates(destination)) destination.filterNot { it.isWhitespace() } else Uri.encode(destination)
        return Uri.parse("google.navigation:q=$query&mode=d")
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

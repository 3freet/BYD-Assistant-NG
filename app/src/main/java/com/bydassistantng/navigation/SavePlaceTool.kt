package com.bydassistantng.navigation

import android.content.Context
import com.bydassistantng.R
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.gemini.SettingsPaths
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.VehicleController
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "SavePlaceTool"

/**
 * "Save this place as Home": keeps where the car is now as a favorite place under a name. Only the name goes to and from
 * the assistant; the position is read on the car and is never said, sent or written to the log.
 */
object SavePlaceTool {
    const val FUNCTION_NAME = "save_place"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Save where the car is right now as one of the user's favorite places, under a name such as Home or " +
            "Work, so that the user can later be taken there by that name. Use only when the user asks to save or remember " +
            "this place, here, or where the car is now, under a name. The position is read from the car: never ask for an " +
            "address or coordinates, and never say them. If a favorite place with that name is already saved, ask the user " +
            "whether to replace it, and call again with replace true only if they agree.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "name" to GeminiSchema(
                    type = "STRING",
                    description = "The name to save it under, as the user said it, e.g. \"Home\", \"Work\" or \"Gym\".",
                ),
                "replace" to GeminiSchema(
                    type = "BOOLEAN",
                    description = "True only after the user agreed to replace the favorite place that has this name.",
                ),
            ),
            required = listOf("name"),
        ),
    )

    /**
     * @param carLink the Car Link when vehicle control is on, for where the car is when the app may not ask Android itself.
     * @param onSaving told the name just before the car's position is looked up, for status display.
     */
    suspend fun handle(context: Context, call: GeminiFunctionCall, carLink: VehicleController?, onSaving: suspend (String) -> Unit): JsonObject {
        val name = (call.args["name"] as? JsonPrimitive)?.content?.trim().orEmpty().take(FavoritePlaces.MAX_NAME_CHARS)
        if (name.isEmpty()) return error("No name was given for the place.")
        val replace = (call.args["replace"] as? JsonPrimitive)?.booleanOrNull == true
        val preferences = EntryPointAccessors.fromApplication(context.applicationContext, NavigationEntryPoint::class.java).preferences()
        val places = preferences.favoritePlaces.first()
        val existing = FavoritePlaces.find(places, name)
        val where = SettingsPaths.favoritePlaces(context)
        if (existing != null && !replace) {
            return error("A favorite place called ${existing.name} is already saved. Ask the user whether to replace it with where the car is now.")
        }
        if (existing == null && places.size >= FavoritePlaces.MAX_PLACES) {
            return error("${FavoritePlaces.MAX_PLACES} favorite places are saved, the most there can be. The user can delete one in $where.")
        }

        onSaving(AppLanguage.string(context, R.string.banner_saving_place, name))
        val position = when (val found = CarLocation.now(context, carLink)) {
            is CarLocationResult.Found -> found.position
            CarLocationResult.NotAllowed -> {
                AppLogger.log(TAG, "Not saved: the app may not read the location and the Car Link had nothing recent")
                return error(
                    "The app may not read the car's precise location yet. The user can allow it once by saving the place in $where " +
                        "with the button for where the car is now.",
                )
            }
            CarLocationResult.Unavailable -> {
                AppLogger.log(TAG, "Not saved: no recent location")
                return error("The car's location is not available right now. Try again in a moment, or save the place in $where.")
            }
        }
        val savedName = existing?.name ?: name
        return when (preferences.saveFavoritePlace(FavoritePlace(savedName, position.destination), replacing = existing?.name)) {
            is SavePlaceResult.Saved -> {
                // The position itself is not logged: only that it was found, how and how old it was.
                AppLogger.log(TAG, "Saved where the car is as the favorite place \"$savedName\" (${position.source}, ${position.ageMs / 1000} s old)")
                buildJsonObject {
                    put("status", "ok")
                    put("note", "Saved where the car is now as the favorite place $savedName.")
                }
            }
            SavePlaceResult.NameTaken -> error("A favorite place called $savedName is already saved.")
            SavePlaceResult.Full -> error("${FavoritePlaces.MAX_PLACES} favorite places are saved, the most there can be. The user can delete one in $where.")
            SavePlaceResult.Empty -> error("No name was given for the place.")
        }
    }

    private fun error(text: String): JsonObject = buildJsonObject {
        put("status", "error")
        put("error", text)
    }
}

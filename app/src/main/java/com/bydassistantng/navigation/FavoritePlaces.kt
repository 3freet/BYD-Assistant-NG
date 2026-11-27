package com.bydassistantng.navigation

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * A place the user is taken to by its name ("Home", "Work"). The name is all the assistant is ever told; the destination
 * stays on the car and goes only to the maps app.
 */
@Serializable
data class FavoritePlace(
    /** What the user calls it, as they typed or said it. */
    val name: String,
    /** What the maps app is handed: an address, a place's name, or coordinates such as "23.588000,58.382898". */
    val destination: String,
)

/** What saving a favorite place came to. */
sealed interface SavePlaceResult {
    data class Saved(val places: List<FavoritePlace>) : SavePlaceResult
    /** Another place already has that name. */
    data object NameTaken : SavePlaceResult
    /** There are [FavoritePlaces.MAX_PLACES] already. */
    data object Full : SavePlaceResult
    /** No name, or nothing to go to. */
    data object Empty : SavePlaceResult
}

/** The rules of the favorite places: how many, how long, which name is which, and how they are stored. */
object FavoritePlaces {
    const val MAX_PLACES = 10
    const val MAX_NAME_CHARS = 40
    const val MAX_DESTINATION_CHARS = 200

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FavoritePlace.serializer())

    /** The stored list; nothing (or something unreadable) is no places. */
    fun decode(raw: String?): List<FavoritePlace> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    fun encode(places: List<FavoritePlace>): String = json.encodeToString(serializer, places)

    /** Two names are one place when they differ only in case or in spaces round them ("home" is "Home"). */
    fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    fun find(places: List<FavoritePlace>, name: String?): FavoritePlace? {
        if (name.isNullOrBlank()) return null
        return places.firstOrNull { sameName(it.name, name) }
    }

    /**
     * [place] saved: added, or put where the place named [replacing] was. Another place with the same name is refused, and a
     * new place once there are [MAX_PLACES]; names and destinations are trimmed and cut to their longest.
     */
    fun save(places: List<FavoritePlace>, place: FavoritePlace, replacing: String? = null): SavePlaceResult {
        val clean = FavoritePlace(place.name.trim().take(MAX_NAME_CHARS), place.destination.trim().take(MAX_DESTINATION_CHARS))
        if (clean.name.isEmpty() || clean.destination.isEmpty()) return SavePlaceResult.Empty
        val index = replacing?.let { old -> places.indexOfFirst { sameName(it.name, old) } } ?: -1
        if (places.withIndex().any { (i, it) -> i != index && sameName(it.name, clean.name) }) return SavePlaceResult.NameTaken
        if (index < 0 && places.size >= MAX_PLACES) return SavePlaceResult.Full
        val updated = places.toMutableList()
        if (index >= 0) updated[index] = clean else updated += clean
        return SavePlaceResult.Saved(updated)
    }

    fun delete(places: List<FavoritePlace>, name: String): List<FavoritePlace> = places.filterNot { sameName(it.name, name) }

    /** Coordinates the way the maps apps take them, with a dot whatever the language of the screens: "23.588000,58.382898". */
    fun coordinates(latitude: Double, longitude: Double): String = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)

    private val COORDINATES = Regex("""\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*""")

    /** Whether [text] is a latitude and a longitude ("23.588, 58.3829"), which goes to the maps app as it is. */
    fun isCoordinates(text: String): Boolean {
        val match = COORDINATES.matchEntire(text) ?: return false
        val latitude = match.groupValues[1].toDouble()
        val longitude = match.groupValues[2].toDouble()
        return latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}

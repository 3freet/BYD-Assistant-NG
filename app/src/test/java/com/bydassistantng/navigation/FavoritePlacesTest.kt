package com.bydassistantng.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The favorite places' rules: names, saving, deleting, storing, and what counts as coordinates. */
class FavoritePlacesTest {
    private val home = FavoritePlace("Home", "Al Khuwair, Muscat")
    private val work = FavoritePlace("Work", "51.500729,-0.124625")

    private fun saved(result: SavePlaceResult): List<FavoritePlace> = (result as SavePlaceResult.Saved).places

    @Test
    fun aNameIsTheSamePlaceWhateverItsCaseOrTheSpacesRoundIt() {
        assertEquals(home, FavoritePlaces.find(listOf(work, home), " home "))
        assertEquals(work, FavoritePlaces.find(listOf(work, home), "WORK"))
        assertNull(FavoritePlaces.find(listOf(work, home), "Gym"))
        assertNull(FavoritePlaces.find(listOf(work, home), ""))
        assertNull(FavoritePlaces.find(listOf(work, home), null))
    }

    @Test
    fun aNewPlaceIsAddedTrimmedAndATakenNameIsRefused() {
        assertEquals(listOf(home, FavoritePlace("Gym", "Some street 1")), saved(FavoritePlaces.save(listOf(home), FavoritePlace(" Gym ", " Some street 1 "))))
        assertEquals(SavePlaceResult.NameTaken, FavoritePlaces.save(listOf(home), FavoritePlace("HOME", "elsewhere")))
        assertEquals(SavePlaceResult.Empty, FavoritePlaces.save(listOf(home), FavoritePlace(" ", "somewhere")))
        assertEquals(SavePlaceResult.Empty, FavoritePlaces.save(listOf(home), FavoritePlace("Gym", " ")))
    }

    @Test
    fun aPlaceIsChangedWhereItIsAndCannotBeRenamedOntoAnother() {
        // The voice command saves a place again under its own name (replace), which is not a clash with itself.
        assertEquals(
            listOf(FavoritePlace("Home", "1.000000,2.000000"), work),
            saved(FavoritePlaces.save(listOf(home, work), FavoritePlace("Home", "1.000000,2.000000"), replacing = "home")),
        )
        assertEquals(listOf(FavoritePlace("House", "Al Khuwair, Muscat"), work), saved(FavoritePlaces.save(listOf(home, work), FavoritePlace("House", "Al Khuwair, Muscat"), replacing = "Home")))
        assertEquals(SavePlaceResult.NameTaken, FavoritePlaces.save(listOf(home, work), FavoritePlace("Work", "x"), replacing = "Home"))
    }

    @Test
    fun noMoreThanTheMostPlacesButAFullListCanStillBeChanged() {
        val full = (1..FavoritePlaces.MAX_PLACES).map { FavoritePlace("Place $it", "Street $it") }
        assertEquals(SavePlaceResult.Full, FavoritePlaces.save(full, FavoritePlace("One more", "Street")))
        assertEquals("Street 0", saved(FavoritePlaces.save(full, FavoritePlace("Place 1", "Street 0"), replacing = "Place 1")).first().destination)
    }

    @Test
    fun longNamesAndDestinationsAreCut() {
        val place = saved(FavoritePlaces.save(emptyList(), FavoritePlace("n".repeat(100), "d".repeat(500)))).single()
        assertEquals(FavoritePlaces.MAX_NAME_CHARS, place.name.length)
        assertEquals(FavoritePlaces.MAX_DESTINATION_CHARS, place.destination.length)
    }

    @Test
    fun deletingTakesThePlaceOfThatNameOnly() {
        assertEquals(listOf(work), FavoritePlaces.delete(listOf(home, work), "HOME"))
        assertEquals(listOf(home, work), FavoritePlaces.delete(listOf(home, work), "Gym"))
    }

    @Test
    fun thePlacesAreStoredAndReadBackAndSomethingUnreadableIsNoPlaces() {
        val places = listOf(home, work, FavoritePlace("البيت", "حي الخوير، مسقط"))
        assertEquals(places, FavoritePlaces.decode(FavoritePlaces.encode(places)))
        assertEquals(emptyList<FavoritePlace>(), FavoritePlaces.decode(null))
        assertEquals(emptyList<FavoritePlace>(), FavoritePlaces.decode("not json"))
    }

    @Test
    fun coordinatesAreWrittenWithADotInEveryLanguage() {
        val before = Locale.getDefault()
        try {
            // German and Arabic write decimals with a comma or with Arabic digits; the maps apps want "51.500729,-0.124625".
            for (locale in listOf(Locale.GERMANY, Locale.forLanguageTag("ar-EG"), Locale.US)) {
                Locale.setDefault(locale)
                assertEquals("51.500729,-0.124625", FavoritePlaces.coordinates(51.5007292, -0.1246254))
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun coordinatesAreToldFromAddresses() {
        assertTrue(FavoritePlaces.isCoordinates("51.500729,-0.124625"))
        assertTrue(FavoritePlaces.isCoordinates(" 23.588, 58.3829 "))
        assertTrue(FavoritePlaces.isCoordinates("-33.8568,151.2153"))
        assertFalse(FavoritePlaces.isCoordinates("Al Khuwair, Muscat"))
        assertFalse(FavoritePlaces.isCoordinates("Street 12, 3"))
        assertFalse(FavoritePlaces.isCoordinates("91.0,10.0"))
        assertFalse(FavoritePlaces.isCoordinates("10.0,181.0"))
    }
}

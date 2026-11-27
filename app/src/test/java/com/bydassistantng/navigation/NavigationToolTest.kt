package com.bydassistantng.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which maps app "take me to …" opens when several can take the navigation link, with no app chosen in Settings and with
 * one (see [NavigationTool.tryOrder]), and the names the assistant says and Settings shows for them.
 */
class NavigationToolTest {
    private val phoneMaps = "com.google.android.apps.maps"
    private val automotiveMaps = "com.google.android.apps.maps.automotive"
    private val osmandPlus = "net.osmand.plus"
    private val osmandFree = "net.osmand"
    private val yandexNavigator = "ru.yandex.yandexnavi"

    @Test
    fun theAutomotiveBuildOfGoogleMapsIsUsedWhenItIsTheOnlyMapsApp() {
        // Issue #6: a DiLink 5.0 head unit with only Google Maps Automotive answered "no navigation app".
        assertEquals(listOf(automotiveMaps), NavigationTool.tryOrder(listOf(automotiveMaps)))
        assertEquals(listOf(osmandFree), NavigationTool.tryOrder(listOf(osmandFree)))
    }

    @Test
    fun aCarThatAlreadyNavigatesKeepsItsApp() {
        // The three apps known before come ahead of the ones added later, whatever order the system lists them in.
        assertEquals(listOf(phoneMaps, automotiveMaps), NavigationTool.tryOrder(listOf(automotiveMaps, phoneMaps)))
        assertEquals(listOf(yandexNavigator, automotiveMaps, osmandFree), NavigationTool.tryOrder(listOf(osmandFree, automotiveMaps, yandexNavigator)))
        assertEquals(listOf(osmandPlus, osmandFree), NavigationTool.tryOrder(listOf(osmandFree, osmandPlus)))
        assertEquals(listOf(phoneMaps, osmandPlus, yandexNavigator), NavigationTool.tryOrder(listOf(yandexNavigator, osmandPlus, phoneMaps)))
    }

    @Test
    fun anyOtherAppThatTakesTheLinkComesAfterTheKnownOnesInTheSystemsOrder() {
        assertEquals(listOf("com.example.navi"), NavigationTool.tryOrder(listOf("com.example.navi")))
        assertEquals(
            listOf(automotiveMaps, "com.example.b", "com.example.a"),
            NavigationTool.tryOrder(listOf("com.example.b", automotiveMaps, "com.example.a")),
        )
    }

    @Test
    fun eachAppIsTriedOnceAndNoAppMeansNothingToTry() {
        assertEquals(listOf(phoneMaps, "com.example.navi"), NavigationTool.tryOrder(listOf("com.example.navi", phoneMaps, "com.example.navi", phoneMaps)))
        assertEquals(emptyList<String>(), NavigationTool.tryOrder(emptyList()))
    }

    @Test
    fun theAppChosenInSettingsIsTriedFirstAndTheOthersAfterIt() {
        // Both Google apps on the car: nothing chosen opens the phone build; choosing the Automotive one opens that.
        assertEquals(listOf(phoneMaps, automotiveMaps), NavigationTool.tryOrder(listOf(automotiveMaps, phoneMaps), chosen = ""))
        assertEquals(listOf(automotiveMaps, phoneMaps), NavigationTool.tryOrder(listOf(phoneMaps, automotiveMaps), chosen = automotiveMaps))
        // An app not known by name can be chosen too; the rest keep their usual order behind it.
        assertEquals(
            listOf("com.example.navi", phoneMaps, yandexNavigator),
            NavigationTool.tryOrder(listOf(yandexNavigator, "com.example.navi", phoneMaps), chosen = "com.example.navi"),
        )
        assertEquals(
            listOf(yandexNavigator, phoneMaps),
            NavigationTool.tryOrder(listOf(phoneMaps, yandexNavigator, yandexNavigator), chosen = yandexNavigator),
        )
    }

    @Test
    fun aChosenAppTheCarNoLongerHasLeavesTheUsualOrder() {
        assertEquals(listOf(phoneMaps, yandexNavigator), NavigationTool.tryOrder(listOf(yandexNavigator, phoneMaps), chosen = automotiveMaps))
        assertEquals(emptyList<String>(), NavigationTool.tryOrder(emptyList(), chosen = automotiveMaps))
    }

    @Test
    fun eachKnownAppHasANameOfItsOwnSoTwoBuildsOfOneAppCanBeToldApart() {
        // Both Google builds may show the same label on the car; the names that are said and shown differ.
        assertEquals("Google Maps", NavigationTool.nameOf(phoneMaps, "Maps"))
        assertEquals("Google Maps Automotive", NavigationTool.nameOf(automotiveMaps, "Maps"))
        assertEquals("OsmAnd+", NavigationTool.nameOf(osmandPlus, "OsmAnd"))
        assertEquals("OsmAnd", NavigationTool.nameOf(osmandFree, "OsmAnd"))
        val names = listOf(phoneMaps, osmandPlus, yandexNavigator, automotiveMaps, osmandFree).map { NavigationTool.nameOf(it, null) }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun anyOtherAppGoesByTheNameItShowsOrItsPackage() {
        assertEquals("Other Navi", NavigationTool.nameOf("com.example.navi", "Other Navi"))
        assertEquals("com.example.navi", NavigationTool.nameOf("com.example.navi", " "))
        assertEquals("com.example.navi", NavigationTool.nameOf("com.example.navi", null))
    }
}

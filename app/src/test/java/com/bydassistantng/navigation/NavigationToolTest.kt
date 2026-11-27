package com.bydassistantng.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which maps app "take me to …" opens when several can take the navigation link (see [NavigationTool.tryOrder]). */
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
}

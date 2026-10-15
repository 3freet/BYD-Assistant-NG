package com.bydassistantng.cluster

import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterScreenToolTest {
    private fun text(json: kotlinx.serialization.json.JsonObject, key: String) = json[key]?.jsonPrimitive?.content

    @Test
    fun everyActionWordMapsToItsAction() {
        for (action in ClusterAction.entries) assertEquals(action, ClusterAction.from(action.word))
        assertEquals(ClusterAction.OPEN_APP, ClusterAction.from(" Open_App "))
        assertNull(ClusterAction.from("fly"))
        assertNull(ClusterAction.from(null))
    }

    @Test
    fun theDeclarationOffersTheThreeActionsAndAnApp() {
        val declaration = ClusterScreenTool.declaration
        assertEquals("cluster_screen", declaration.name)
        val properties = declaration.parameters!!.properties!!
        assertEquals(ClusterAction.entries.map { it.word }, properties.getValue("action").enum)
        assertTrue("app" in properties)
        // A YouTube video can be asked for by what the user said; only the action is required.
        assertTrue("video" in properties)
        assertEquals(listOf("action"), declaration.parameters!!.required)
    }

    @Test
    fun theOpenCommandCarriesWhatToDoWithBydMapsAfterwards() {
        assertEquals("disable", ClusterScreenTool.policyWord(keepMapsDisabled = true))
        assertEquals("keep", ClusterScreenTool.policyWord(keepMapsDisabled = false))
    }

    @Test
    fun closingKeepsOrDisablesBydMapsAsChosen() {
        assertEquals("close disable", ClusterScreenTool.closeLine(keepMapsDisabled = true))
        assertEquals("close keep", ClusterScreenTool.closeLine(keepMapsDisabled = false))
    }

    @Test
    fun anOpenedAppIsReportedAsShownOnTheCluster() {
        val answer = ClusterScreenTool.answer(
            VehicleDispatchResult.Success("opened=ru.yandex.yandexnavi display=3 type=4"),
            ClusterAction.OPEN_APP,
            "Yandex Navi",
        )
        assertEquals("ok", text(answer, "status"))
        assertTrue(text(answer, "note")!!.contains("Yandex Navi"))
        assertTrue(text(answer, "instruction")!!.contains("Do not call this again"))
    }

    @Test
    fun aClosedClusterSaysTheGaugesAreBack() {
        val answer = ClusterScreenTool.answer(
            VehicleDispatchResult.Success("closed=ru.yandex.yandexnavi type=restored maps=disabled"),
            ClusterAction.CLOSE,
        )
        assertEquals("ok", text(answer, "status"))
        assertTrue(text(answer, "note")!!.contains("gauges"))
    }

    @Test
    fun statusNamesWhatIsOnTheCluster() {
        val busy = ClusterScreenTool.answer(
            VehicleDispatchResult.Success("type=4 top=ru.yandex.yandexnavi apps=ru.yandex.yandexnavi maps=enabled display=3"),
            ClusterAction.STATUS,
        )
        assertTrue(text(busy, "note")!!.contains("ru.yandex.yandexnavi"))
        assertEquals("ru.yandex.yandexnavi", text(busy, "top_package"))
        val idle = ClusterScreenTool.answer(
            VehicleDispatchResult.Success("type=1 top=none apps= maps=disabled display=3"),
            ClusterAction.STATUS,
        )
        assertEquals("No app is on the instrument cluster.", text(idle, "note"))
    }

    @Test
    fun aFailureIsPassedOnInPlainWords() {
        val answer = ClusterScreenTool.answer(
            VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "BYD Maps did not become available"),
            ClusterAction.OPEN_APP,
        )
        assertEquals("error", text(answer, "status"))
        assertEquals("BYD Maps did not become available", text(answer, "error"))
    }
}

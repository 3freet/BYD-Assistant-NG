package com.bydassistantng.passenger

import com.bydassistantng.apps.InstalledApp
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassengerScreenToolTest {
    private val installed = listOf(
        InstalledApp("YouTube", "com.google.android.youtube"),
        InstalledApp("Spotify", "com.spotify.music"),
        InstalledApp("Settings", "com.byd.carsettings"),
        InstalledApp("Trip Recorder NG", "org.triprecorderng"),
    )

    @Test
    fun anAppOnTheMainUsersListIsFoundByName() {
        val target = PassengerScreenTool.resolve("YouTube", installed)
        assertEquals(PassengerTarget.Main(InstalledApp("YouTube", "com.google.android.youtube")), target)
        // "on the passenger screen" and filler words around the name do not matter.
        assertEquals(target, PassengerScreenTool.resolve("the YouTube app on the passenger screen", installed))
    }

    @Test
    fun theAppsOfThePassengerScreenAreFoundByTheirWords() {
        assertEquals(PassengerTarget.PassengerOnly("Video player", "com.byd.videoplay.fse"), PassengerScreenTool.resolve("video", installed))
        assertEquals(PassengerTarget.PassengerOnly("Karaoke", "com.tencentbyd.karaokecar.fse"), PassengerScreenTool.resolve("karaoke", installed))
        assertEquals(PassengerTarget.PassengerOnly("Photos", "com.byd.auto_photo.fse"), PassengerScreenTool.resolve("the photos app", installed))
    }

    @Test
    fun settingsOnThePassengerScreenMeansItsOwnSettings() {
        assertEquals(PassengerTarget.PassengerOnly("Passenger settings", "com.byd.carsettings.fse"), PassengerScreenTool.resolve("settings", installed))
    }

    @Test
    fun anUnknownNameIsNotGuessed() {
        assertEquals(PassengerTarget.None, PassengerScreenTool.resolve("blorp", installed))
        assertEquals(PassengerTarget.None, PassengerScreenTool.resolve("   ", installed))
    }

    @Test
    fun everyActionWordMapsToItsAction() {
        for (action in PassengerAction.entries) assertEquals(action, PassengerAction.from(action.word))
        assertEquals(PassengerAction.SCREEN_OFF, PassengerAction.from(" Screen_Off "))
        assertNull(PassengerAction.from("reboot"))
        assertNull(PassengerAction.from(null))
    }

    @Test
    fun theDeclarationOffersExactlyTheActionsAndNothingElseIsRequired() {
        val declaration = PassengerScreenTool.declaration
        assertEquals("passenger_screen", declaration.name)
        val parameters = checkNotNull(declaration.parameters)
        assertEquals(listOf("action"), parameters.required)
        assertEquals(PassengerAction.entries.map { it.word }, parameters.properties?.getValue("action")?.enum)
    }

    @Test
    fun theHelpersAnswerWordsAreReadAsKeyValuePairs() {
        val words = PassengerScreenTool.words("closed=com.google.android.youtube kept= display=2")
        assertEquals("com.google.android.youtube", words["closed"])
        assertEquals("", words["kept"])
        assertEquals("2", words["display"])
        assertEquals(emptyMap<String, String>(), PassengerScreenTool.words(null))
    }

    @Test
    fun anOpenedAppIsReportedByItsLabel() {
        val answer = PassengerScreenTool.answer(
            VehicleDispatchResult.Success("opened=com.google.android.youtube user=0 display=2"),
            PassengerScreenTool.AnswerKind.OPEN,
            label = "YouTube",
        )
        assertEquals("ok", answer.getValue("status").jsonPrimitive.content)
        assertTrue(answer.getValue("note").jsonPrimitive.content.startsWith("YouTube is now open"))
    }

    @Test
    fun aCloseWithNothingOpenSaysSo() {
        val answer = PassengerScreenTool.answer(VehicleDispatchResult.Success("closed= kept="), PassengerScreenTool.AnswerKind.CLOSE)
        assertEquals("Nothing was open on the passenger screen.", answer.getValue("note").jsonPrimitive.content)
    }

    @Test
    fun aFailureCarriesTheHelpersSentenceToTheModel() {
        val answer = PassengerScreenTool.answer(
            VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "com.x did not come up on the passenger screen"),
            PassengerScreenTool.AnswerKind.OPEN,
        )
        assertEquals("error", answer.getValue("status").jsonPrimitive.content)
        assertNotNull(answer["error"])
    }
}

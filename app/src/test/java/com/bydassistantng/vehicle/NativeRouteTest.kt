package com.bydassistantng.vehicle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRouteTest {
    @After
    fun backToTheDefault() {
        VehicleCommandRegistry.useAcRoute(null)
        VehicleCommandRegistry.useCarTable(null)
    }

    private fun reply(status: Int, value: Int = 0) = NativeAutoservice.Reply(status, value)

    // ── What the service says ─────────────────────────────────────────────────────────────────────

    @Test
    fun aReplyIsTheStatusThenTheValueAndAWriteHasOnlyAStatus() {
        assertEquals(reply(0, 22), NativeAutoservice.decode(listOf(0, 22)))
        assertEquals(reply(1, 0), NativeAutoservice.decode(listOf(1)))
        assertNull(NativeAutoservice.decode(emptyList()))
        // Only the first two words are the answer.
        assertEquals(reply(0, 5), NativeAutoservice.decode(listOf(0, 5, 99)))
    }

    @Test
    fun aReadWorkedWhenTheStatusIsZeroAndTheValueIsARealOne() {
        assertEquals(22, NativeAnswer.readValue(reply(0, 22)))
        assertEquals(0, NativeAnswer.readValue(reply(0, 0)))
        assertNull(NativeAnswer.readValue(reply(0, -10011)))
        assertNull(NativeAnswer.readValue(reply(0, 65535)))
        assertNull(NativeAnswer.readValue(reply(-1, 22)))
        assertNull(NativeAnswer.readValue(reply(1, 22)))
        assertNull(NativeAnswer.readValue(null))
    }

    @Test
    fun aWriteTheServiceTookIsAcceptedWhateverItsPositiveStatusAndTheNoteSaysWhichWayItCame() {
        for (status in listOf(0, 1, 7)) {
            val result = NativeAnswer.writeResult(reply(status))
            assertTrue("$status", result is VehicleDispatchResult.Success)
            assertEquals("HAL returned $status${NativeAnswer.NATIVE_TAG}", (result as VehicleDispatchResult.Success).note)
        }
    }

    @Test
    fun aTemperatureBelowZeroIsARealReadingEvenThoughAPlainStateReadDropsNegatives() {
        assertEquals(-12, NativeAnswer.rawValue(reply(0, -12)))
        assertNull(NativeAnswer.rawValue(reply(-1, -12)))
        assertNull(NativeAnswer.rawValue(null))
        assertEquals(-12, OutsideTemperatureTool.reading(-12, 1).celsius)
        assertEquals(35, OutsideTemperatureTool.reading(35, 1).celsius)
        // The cluster's own "no reading", and the service's, are not temperatures.
        assertNull(OutsideTemperatureTool.reading(195, 1).celsius)
        assertNull(OutsideTemperatureTool.reading(-10011, 1).celsius)
        assertNull(OutsideTemperatureTool.reading(null, 1).celsius)
    }

    @Test
    fun aWriteTheServiceRefusedIsAFailureWithTheKindOfRefusal() {
        fun failure(r: NativeAutoservice.Reply?) = NativeAnswer.writeResult(r) as VehicleDispatchResult.Failure

        assertEquals(VehicleDispatchError.SECURITY_DENIED, failure(reply(NativeAnswer.PERMISSION_DENIED)).error)
        assertEquals(VehicleDispatchError.NOT_AVAILABLE_ON_THIS_CAR, failure(reply(NativeAnswer.NOT_AVAILABLE)).error)
        assertEquals(VehicleDispatchError.INVOCATION_FAILED, failure(reply(-3)).error)
        assertEquals("The car's service refused it (answer -3).", failure(reply(-3)).detail)
        assertEquals(VehicleDispatchError.INVOCATION_FAILED, failure(null).error)
        // Whatever is refused, the person is told nothing was sent.
        for (r in listOf(null, reply(NativeAnswer.PERMISSION_DENIED), reply(NativeAnswer.NOT_AVAILABLE))) {
            assertTrue(failure(r).detail!!.endsWith("Nothing was sent."))
        }
    }

    @Test
    fun theModelIsNeverToldWhichWayANoteCame() {
        assertEquals("confirmed: the car now reports 22", CommandAnswers.forModel("confirmed: the car now reports 22${NativeAnswer.NATIVE_TAG}"))
        assertEquals(CommandAnswers.UNCHECKED_NOTE, CommandAnswers.forModel("HAL returned 1${NativeAnswer.NATIVE_TAG}"))
        assertEquals(CommandAnswers.UNCHECKED_NOTE, CommandAnswers.forModel("sent, but the car wouldn't report a value to confirm it${NativeAnswer.NATIVE_TAG}"))
        assertTrue(CommandAnswers.forModel("under way: now at 33, heading for 40${NativeAnswer.NATIVE_TAG}")!!.startsWith("On its way: now at 33, heading for 40."))
        // And a note from the device class is as it was.
        assertEquals("confirmed: the car now reports 22", CommandAnswers.forModel("confirmed: the car now reports 22"))
    }

    // ── Which way the helper goes ────────────────────────────────────────────────────────────────

    @Test
    fun theModeIsReadFromTheTestPropertyAndAnythingElseIsAutomatic() {
        assertEquals(NativeMode.ALWAYS, NativeMode.parse("always"))
        assertEquals(NativeMode.ALWAYS, NativeMode.parse(" ALWAYS "))
        assertEquals(NativeMode.NEVER, NativeMode.parse("never"))
        assertEquals(NativeMode.AUTO, NativeMode.parse("auto"))
        assertEquals(NativeMode.AUTO, NativeMode.parse(""))
        assertEquals(NativeMode.AUTO, NativeMode.parse(null))
        assertEquals(NativeMode.AUTO, NativeMode.parse("sometimes"))
        assertTrue(NativeMode.PROPERTY.length <= 31)
        assertTrue(AcRoute.PROPERTY.length <= 31)
    }

    @Test
    fun theDeviceClassIsUsedUntilTheCarRefusesItAndThenTheServiceIs() {
        assertFalse(NativeRoute.preferNative(NativeMode.AUTO, kitRefusedBefore = false))
        assertTrue(NativeRoute.preferNative(NativeMode.AUTO, kitRefusedBefore = true))
        assertTrue(NativeRoute.preferNative(NativeMode.ALWAYS, kitRefusedBefore = false))
        assertFalse(NativeRoute.preferNative(NativeMode.NEVER, kitRefusedBefore = true))
    }

    @Test
    fun aRefusalIsFollowedByTheServiceExceptWhereItIsForbidden() {
        assertTrue(NativeRoute.fallBack(NativeMode.AUTO))
        assertTrue(NativeRoute.fallBack(NativeMode.ALWAYS))
        assertFalse(NativeRoute.fallBack(NativeMode.NEVER))
    }

    @Test
    fun aClimateRouteCanBeSetByHandForATestAndNothingElseIsOne() {
        assertEquals(AcRoute.DEVICE, AcRoute.forced("device"))
        assertEquals(AcRoute.SERVICE, AcRoute.forced(" Service "))
        assertEquals(AcRoute.NONE, AcRoute.forced("none"))
        assertNull(AcRoute.forced(""))
        assertNull(AcRoute.forced(null))
        assertNull(AcRoute.forced("native"))
    }

    // ── The climate controls the service can write ────────────────────────────────────────────────

    private val acIds = VehicleCommandRegistry.entries.filter { it.invocation.type == "acBinderProperty" }.map { it.id }

    private fun carThatAgrees(): Map<String, Int> = FeatureNameCheck.references().associate { it.name to it.number } +
        VehicleCommandRegistry.entries.mapNotNull { it.withoutAcService }.flatMap {
            listOfNotNull(
                it.stateFeatureName?.let { name -> name to it.stateFeatureId!! },
                it.requires?.featureName?.let { name -> name to it.requires.featureId },
                it.nativeFeatureName?.let { name -> name to it.nativeFeatureId!! },
            )
        }

    @Test
    fun everyClimateDeviceRouteNamesTheOneControlItWritesAndTheStateItReadsBack() {
        assertEquals(10, acIds.size)
        for (entry in VehicleCommandRegistry.entries.filter { it.id in acIds }) {
            val route = checkNotNull(entry.withoutAcService)
            assertNotNull("${entry.id} control", route.nativeFeatureId)
            // A control is a SET, and the state it reports is the same name without it.
            assertTrue(entry.id, route.nativeFeatureName!!.startsWith("Ac.") && route.nativeFeatureName.endsWith("_SET"))
            assertEquals(entry.id, route.nativeFeatureName.removeSuffix("_SET"), route.stateFeatureName)
        }
    }

    @Test
    fun theNativeControlsAreTheCarsOwnNumbersAndComeWithTheDeviceRoute() {
        val table = carThatAgrees().mapValues { it.value + 3 }
        val onDevice = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, table, AcRoute.DEVICE)
        val onService = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, table, AcRoute.SERVICE)

        val fan = checkNotNull(onDevice.known.find { it.id == "ac.fan" })
        assertEquals(table.getValue("Ac.AC_WIND_LEVEL_SET"), fan.nativeFeatureId)
        assertEquals("Ac.AC_WIND_LEVEL_SET", fan.nativeFeatureName)
        assertEquals(1000, fan.deviceType)
        // The service route does not carry it: it never writes a feature.
        assertNull(onService.known.find { it.id == "ac.fan" }!!.nativeFeatureId)
    }

    @Test
    fun aCarThatDoesNotNameTheControlStillGetsTheCommandJustNotThatRoute() {
        val table = carThatAgrees() - "Ac.AC_WIND_LEVEL_SET"
        val here = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, table, AcRoute.DEVICE)

        val fan = checkNotNull(here.known.find { it.id == "ac.fan" })
        assertNull(fan.nativeFeatureId)
        assertNull(fan.nativeFeatureName)
        assertTrue(fan.invocation is VehicleInvocation.NamedMethod)
        assertFalse("ac.fan" in here.missingNames)
    }

    @Test
    fun anOrdinaryCommandHasNoNativeControlOfItsOwnBecauseItsOwnControlIsTheOne() {
        val window = checkNotNull(VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5).known.find { it.id == "window.driver" })

        assertNull(window.nativeFeatureId)
        assertTrue(window.invocation is VehicleInvocation.GenericFeatureSet)
    }

    // ── The climate service under its other name ──────────────────────────────────────────────────

    @Test
    fun theClimateServiceHasTwoNamesAndTheOtherIsNotTheOneTheCommandsWereWrittenFor() {
        assertEquals("byd_airconditioning", AcService.PRIMARY)
        assertEquals("airconditioning", AcService.ALIAS)
        assertEquals("com.byd.ac.IBydAcService", AcService.MASTER_DESCRIPTOR)
        assertEquals(AcService.PRIMARY, ReflectionVehicleController.AC_SERVICE_NAME)
    }
}

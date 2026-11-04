package com.bydassistantng.vehicle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcRouteTest {
    @After
    fun backToTheDefault() {
        VehicleCommandRegistry.useAcRoute(null)
        VehicleCommandRegistry.useCarTable(null)
    }

    private val everything = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5)
    private val acIds = VehicleCommandRegistry.entries.filter { it.invocation.type == "acBinderProperty" }.map { it.id }

    /** The names the A/C commands read when they go through the device, each with the number the registry has for it. */
    private fun acTable(): Map<String, Int> = buildMap {
        for (route in VehicleCommandRegistry.entries.mapNotNull { it.withoutAcService }) {
            put(route.stateFeatureName!!, route.stateFeatureId!!)
            route.requires?.let { put(it.featureName!!, it.featureId) }
        }
    }

    /** A car whose table gives every name the registry uses the number the registry has for it, the A/C device's states included. */
    private fun carThatAgrees(): Map<String, Int> = FeatureNameCheck.references().associate { it.name to it.number } + acTable()

    private fun CarCommands.command(id: String) = checkNotNull(known.find { it.id == id }) { "$id is not offered" }

    // ── Which route a car has ─────────────────────────────────────────────────────────────────────

    @Test
    fun aCarWithTheServiceUsesItWhateverElseItHas() {
        assertEquals(AcRoute.SERVICE, AcRoute.choose(serviceAnswers = true, deviceUsable = true))
        assertEquals(AcRoute.SERVICE, AcRoute.choose(serviceAnswers = true, deviceUsable = false))
    }

    @Test
    fun aCarWithoutTheServiceUsesTheClimateDeviceIfItHasOne() {
        assertEquals(AcRoute.DEVICE, AcRoute.choose(serviceAnswers = false, deviceUsable = true))
        assertEquals(AcRoute.NONE, AcRoute.choose(serviceAnswers = false, deviceUsable = false))
    }

    @Test
    fun aCarWhoseServicesCannotBeAskedKeepsTheRouteTheCommandsWereVerifiedOn() {
        assertEquals(AcRoute.SERVICE, AcRoute.choose(serviceAnswers = null, deviceUsable = true))
        assertEquals(AcRoute.SERVICE, AcRoute.choose(serviceAnswers = null, deviceUsable = false))
    }

    // ── What the registry says about the device route ─────────────────────────────────────────────────

    @Test
    fun everyAcCommandHasADeviceRouteThatReadsItsResultBack() {
        assertEquals(10, acIds.size)
        for (entry in VehicleCommandRegistry.entries.filter { it.id in acIds }) {
            val route = checkNotNull(entry.withoutAcService) { "${entry.id} has no device route" }
            assertEquals(entry.id, "namedMethod", route.invocation.type)
            assertEquals(entry.id, "ac", route.invocation.deviceClass)
            assertNotNull("${entry.id} state id", route.stateFeatureId)
            assertTrue("${entry.id} state name", route.stateFeatureName!!.startsWith("Ac."))
            // The typed methods of the climate device take the control source first: 1 is the voice, 0 the screen's own.
            assertEquals(entry.id, 1, route.invocation.argsTemplate!!.first())
        }
    }

    @Test
    fun theDeviceRouteTakesTheSameValuesAsTheServiceRoute() {
        // The user and the model deal in the same values on every car: the route is not part of what they say.
        val onDevice = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, null, AcRoute.DEVICE)
        for (id in acIds) {
            assertEquals(id, everything.command(id).parameter, onDevice.command(id).parameter)
            assertEquals(id, everything.command(id).displayName, onDevice.command(id).displayName)
        }
    }

    @Test
    fun thePowerIsTurnedOnAndOffByTheTwoMethodsThatDoIt() {
        val power = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, null, AcRoute.DEVICE).command("ac.power")
        val call = power.invocation as VehicleInvocation.NamedMethod

        assertEquals("start", call.methodFor(1))
        assertEquals("stop", call.methodFor(0))
        assertEquals(listOf(1), call.argsTemplate)
    }

    @Test
    fun aTypedSetterWithoutAnOffMethodTakesTheValueAsItsArgument() {
        val fan = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, null, AcRoute.DEVICE).command("ac.fan")
        val call = fan.invocation as VehicleInvocation.NamedMethod

        assertEquals("setAcWindLevel", call.methodFor(0))
        assertEquals("setAcWindLevel", call.methodFor(5))
        assertEquals(listOf(1, null), call.argsTemplate)
    }

    @Test
    fun aMethodForOffTakesNoValueSlotAndAnythingElseTakesExactlyOne() {
        VehicleInvocation.NamedMethod("ac", "start", listOf("int"), listOf(1), offMethodName = "stop")
        VehicleInvocation.NamedMethod("ac", "setAcWindLevel", listOf("int", "int"), listOf(1, null))
        for (build in listOf<() -> Unit>(
            { VehicleInvocation.NamedMethod("ac", "start", listOf("int"), listOf(null), offMethodName = "stop") },
            { VehicleInvocation.NamedMethod("ac", "setAcWindLevel", listOf("int", "int"), listOf(1, 2)) },
        )) {
            val refused = try {
                build()
                false
            } catch (_: IllegalArgumentException) {
                true
            }
            assertTrue(refused)
        }
    }

    // ── A car with the service, or one whose services are not known ──────────────────────────────────

    @Test
    fun aCarWithTheServiceHasTheAcCommandsAsTheyWereVerified() {
        val withService = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, null, AcRoute.SERVICE)

        assertEquals(everything.known, withService.known)
        for (id in acIds) assertTrue(id, withService.command(id).invocation is VehicleInvocation.AcBinderProperty)
    }

    @Test
    fun theAppWhichDoesNotLookKeepsTheServiceRoute() {
        // The app never reads the services; what the helper found reaches it as the ids the car cannot do.
        assertNull(VehicleCommandRegistry.acRoute)
        for (id in acIds) assertTrue(id, everything.command(id).invocation is VehicleInvocation.AcBinderProperty)
    }

    // ── A car without the service ────────────────────────────────────────────────────────────────────

    @Test
    fun aCarWithoutTheServiceHasTheAcCommandsAsTypedCallsOnTheClimateDevice() {
        val here = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, carThatAgrees(), AcRoute.DEVICE)

        for (id in acIds) {
            val command = here.command(id)
            assertTrue(id, command.invocation is VehicleInvocation.NamedMethod)
            assertEquals(id, 1000, command.deviceType)
            assertNotNull("$id reads its result back", command.stateFeatureId)
        }
        assertTrue(here.missingNames.isEmpty())
        // Nothing but the A/C changed.
        assertEquals(everything.known.filter { it.id !in acIds }, here.known.filter { it.id !in acIds })
    }

    @Test
    fun theStatesAreTheCarsOwnNumbers() {
        val table = carThatAgrees() + acTable().mapValues { it.value + 5 }
        val here = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, table, AcRoute.DEVICE)

        assertEquals(table.getValue("Ac.AC_WIND_LEVEL"), here.command("ac.fan").stateFeatureId)
        assertEquals(table.getValue("Ac.AC_POWER_STATE"), here.command("ac.power").stateFeatureId)
        assertEquals(table.getValue("Ac.AC_TEMPERATURE_UNIT"), here.command("ac.temperature").requires!!.featureId)
        // The value the unit must read is Celsius whatever the car numbers it.
        assertEquals(1, here.command("ac.temperature").requires!!.equals)
    }

    @Test
    fun theTemperatureIsOnlySetOnACarShowingCelsius() {
        val temperature = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, null, AcRoute.DEVICE).command("ac.temperature")

        assertEquals(1, temperature.requires!!.equals)
        assertEquals("Ac.AC_TEMPERATURE_UNIT", temperature.requires!!.featureName)
        assertTrue(temperature.requires!!.message.endsWith("Nothing was sent."))
        // The call names Celsius itself as its last argument: the method writes the unit along with the temperature.
        assertEquals(1, (temperature.invocation as VehicleInvocation.NamedMethod).argsTemplate.last())
    }

    @Test
    fun aCarThatDoesNotNameTheUnitDoesNotGetTheTemperatureButKeepsTheRest() {
        val table = carThatAgrees() - "Ac.AC_TEMPERATURE_UNIT"
        val here = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, table, AcRoute.DEVICE)

        assertFalse("ac.temperature" in here.known.map { it.id })
        assertEquals(listOf("Ac.AC_TEMPERATURE_UNIT"), here.missingNames["ac.temperature"])
        assertEquals(acIds.size - 1, here.known.count { it.id in acIds })
    }

    @Test
    fun aCarThatDoesNotNameAStateStillGetsTheCommandWithoutItsReadBack() {
        val table = carThatAgrees() - "Ac.AC_WIND_LEVEL"
        val here = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, table, AcRoute.DEVICE)

        assertNull(here.command("ac.fan").stateFeatureId)
        assertNull(here.command("ac.fan").stateFeatureName)
        assertTrue(here.command("ac.fan").invocation is VehicleInvocation.NamedMethod)
    }

    @Test
    fun theTableOfACarWithoutTheServiceIsOnlyAskedForTheNamesTheDeviceRouteReads() {
        assertEquals(acTable().keys, VehicleCommandRegistry.deviceRouteNames().toSet())
        assertTrue(VehicleCommandRegistry.deviceRouteNames().all { it.startsWith("Ac.") })
    }

    // ── A car with neither ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aCarWithNeitherHasNoAcCommandsAndTheRestIsUnchanged() {
        val here = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5, null, AcRoute.NONE)

        assertTrue(here.known.none { it.id in acIds })
        assertEquals(acIds.toSet(), here.withheld)
        for (id in acIds) assertEquals(id, listOf(ReflectionVehicleController.AC_SERVICE_NAME), here.missingNames[id])
        assertEquals(everything.known.filter { it.id !in acIds }, here.known)
    }

    @Test
    fun theRegistryFollowsWhatTheHelperFound() {
        VehicleCommandRegistry.useAcRoute(AcRoute.NONE)
        assertTrue(VehicleCommandRegistry.known.none { it.id in acIds })
        assertEquals(acIds.toSet(), VehicleCommandRegistry.unavailable.keys.filter { it in acIds }.toSet())

        VehicleCommandRegistry.useAcRoute(AcRoute.DEVICE)
        assertTrue(VehicleCommandRegistry.known.filter { it.id in acIds }.all { it.invocation is VehicleInvocation.NamedMethod })

        VehicleCommandRegistry.useAcRoute(AcRoute.SERVICE)
        assertTrue(VehicleCommandRegistry.known.filter { it.id in acIds }.all { it.invocation is VehicleInvocation.AcBinderProperty })
    }

    // ── What the log says ────────────────────────────────────────────────────────────────────────────

    @Test
    fun theServicesThatLookLikeTheClimateOrTheVehicleAreListedByName() {
        val line = CarDiagnostics.relatedServices(
            listOf("activity", "byd_ac", "autoservice", "byd_airconditioning", "audio", "hvac_manager", "vehicle", "window", "bydcdr", "ac"),
        )

        assertEquals("ac,byd_ac,bydcdr,hvac_manager,vehicle", line)
    }

    @Test
    fun noServiceNamedLikeThatMeansNothingIsListed() {
        assertNull(CarDiagnostics.relatedServices(listOf("activity", "audio", "window", "autoservice", "byd_airconditioning")))
        assertEquals("?", CarDiagnostics.relatedServices(null))
    }

    @Test
    fun aLongListIsCut() {
        val many = (1..60).map { "byd_service_%02d".format(it) }
        val line = CarDiagnostics.relatedServices(many, limit = 5)!!

        assertEquals("byd_service_01,byd_service_02,byd_service_03,byd_service_04,byd_service_05,+55 more", line)
    }

    @Test
    fun aServiceNameCannotBreakTheLine() {
        val line = CarDiagnostics.relatedServices(listOf("byd weird name\nsecond line", "byd_" + "x".repeat(100)))!!

        assertEquals("byd_weird_name_second_line,byd_" + "x".repeat(36), line)
    }
}

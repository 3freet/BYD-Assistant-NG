package com.bydassistantng.vehicle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleCommandResolutionTest {
    private val everything = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5)

    @After
    fun backToTheDefault() {
        VehicleCommandRegistry.platform = DiLinkPlatform.UNKNOWN
        VehicleCommandRegistry.useCarTable(null)
        VehicleCommandRegistry.useReportedUnavailable(emptySet())
    }

    /** A car whose table gives every name the number the registry has for it. */
    private fun carThatAgrees(): Map<String, Int> = FeatureNameCheck.references().associate { it.name to it.number }

    private fun CarCommands.command(id: String) = checkNotNull(known.find { it.id == id }) { "$id is not offered" }

    private fun command(id: String) = everything.command(id)

    private fun on(table: Map<String, Int>, platform: DiLinkPlatform = DiLinkPlatform.DILINK_5) = VehicleCommandRegistry.commandsFor(platform, table)

    // ── Before the car's table is known, and on the car the numbers were verified on ───────────────────

    @Test
    fun withoutACarTableTheWholeRegistryIsOfferedWithItsOwnNumbersAsTheAppAlwaysHad() {
        assertEquals(VehicleCommandRegistry.entries.size, everything.known.size)
        assertTrue(everything.withheld.isEmpty())
        assertTrue(everything.missingNames.isEmpty())
        // The registry itself, before the platform is known, is the careful one: it is not a recognised DiLink 5.
        assertEquals(VehicleCommandRegistry.commandsFor(DiLinkPlatform.UNKNOWN).known, VehicleCommandRegistry.known)
        assertEquals(
            everything.known.map { it.copy(requiresReadback = false) },
            VehicleCommandRegistry.known.map { it.copy(requiresReadback = false) },
        )
    }

    @Test
    fun onTheCarTheNumbersWereVerifiedOnNothingChanges() {
        val here = on(carThatAgrees())

        assertEquals(everything.known, here.known)
        assertTrue(here.missingNames.isEmpty())
    }

    // ── A car that numbers things differently ─────────────────────────────────────────────────────────

    @Test
    fun aCarThatNumbersAControlDifferentlyIsSentToItsOwnNumber() {
        val driver = command("window.driver")
        val here = on(carThatAgrees() + (driver.featureName!! to 589299768))

        assertEquals(589299768, here.command("window.driver").featureId)
        assertEquals(driver.copy(featureId = 589299768), here.command("window.driver"))
        // Nothing else moved.
        assertEquals(everything.known.filter { it.id != "window.driver" }, here.known.filter { it.id != "window.driver" })
    }

    @Test
    fun theStateTheRequirementAndTheOverridesAreRenumberedToo() {
        val cooling = command("fridge.cool.temperature")
        val massage = command("seat.driver.massage")
        val table = carThatAgrees() +
            (cooling.stateFeatureName!! to 111) + (cooling.requires!!.featureName!! to 222) + (massage.overrides.single().featureName!! to 333)
        val here = on(table)

        assertEquals(111, here.command("fridge.cool.temperature").stateFeatureId)
        assertEquals(222, here.command("fridge.cool.temperature").requires!!.featureId)
        assertEquals(cooling.requires!!.equals, here.command("fridge.cool.temperature").requires!!.equals)
        assertEquals(333, here.command("seat.driver.massage").overrides.single().featureId)
        assertEquals(massage.overrides.single().sendValue, here.command("seat.driver.massage").overrides.single().sendValue)
    }

    @Test
    fun whatTheUserSaysAndTheModelSendsIsNeverTouchedByTheCarsNumbers() {
        val renumbered = FeatureNameCheck.references().associate { it.name to it.number + 7 }
        val here = on(renumbered)

        for (command in here.known) {
            val original = command(command.id)
            assertEquals(command.id, original.parameter, command.parameter)
            assertEquals(command.id, original.valueOffset, command.valueOffset)
            assertEquals(command.id, original.inverted, command.inverted)
            assertEquals(command.id, original.invocation, command.invocation)
            assertEquals(command.id, original.deviceType, command.deviceType)
        }
    }

    @Test
    fun theControlsThatUseNoFeatureNumbersOfTheCarsTableAreNeverTouched() {
        val here = on(emptyMap())

        for (id in everything.known.filter { it.id.startsWith("ac.") || it.id.startsWith("dashcam.") || it.id == "audio.volume" || it.id == "screen.rotation" }.map { it.id }) {
            assertEquals(command(id), here.command(id))
        }
    }

    // ── A name the car does not have ──────────────────────────────────────────────────────────────

    @Test
    fun aCommandWhoseControlTheCarDoesNotNameIsWithheldWithTheName() {
        val heating = command("seat.driver.heating")
        val here = on(carThatAgrees() - heating.featureName!!)

        assertFalse("seat.driver.heating" in here.known.map { it.id })
        assertEquals(listOf(heating.featureName), here.missingNames["seat.driver.heating"])
        assertTrue("seat.driver.heating" in here.withheld)
        // Only that one.
        assertEquals(everything.known.size - 1, here.known.size)
    }

    @Test
    fun aMissingStateOnlyTakesTheReadBackAway() {
        val heating = command("seat.driver.heating")
        val here = on(carThatAgrees() - heating.stateFeatureName!!)

        val offered = here.command("seat.driver.heating")
        assertNull(offered.stateFeatureId)
        assertNull(offered.stateFeatureName)
        assertEquals(heating.featureId, offered.featureId)
        assertTrue(here.missingNames.isEmpty())
    }

    @Test
    fun aMissingPreconditionWithholdsTheCommandItGuards() {
        val cooling = command("fridge.cool.temperature")
        val here = on(carThatAgrees() - cooling.requires!!.featureName!!)

        assertFalse("fridge.cool.temperature" in here.known.map { it.id })
        assertFalse("fridge.heat.temperature" in here.known.map { it.id })
        assertTrue("fridge.mode" in here.known.map { it.id })
    }

    @Test
    fun aMissingOverrideControlWithholdsTheWholeCommandBecauseOffIsOneOfItsValues() {
        val massage = command("seat.driver.massage")
        val here = on(carThatAgrees() - massage.overrides.single().featureName!!)

        assertFalse("seat.driver.massage" in here.known.map { it.id })
        assertTrue("seat.passenger.massage" !in here.known.map { it.id } || command("seat.passenger.massage").overrides.single().featureName != massage.overrides.single().featureName)
    }

    @Test
    fun aTypedMethodStaysOfferedWithoutTheStateItIsConfirmedBy() {
        val colour = command("ambient.color")
        val here = on(carThatAgrees() - colour.stateFeatureName!!)

        assertNull(here.command("ambient.color").stateFeatureId)
        assertTrue(here.missingNames.isEmpty())
    }

    @Test
    fun aCarWithNoTableEntriesAtAllOffersOnlyWhatDoesNotNeedThem() {
        val here = on(emptyMap())

        assertTrue(here.known.none { it.invocation == VehicleInvocation.GenericFeatureSet })
        assertTrue(here.known.any { it.id.startsWith("ac.") })
        assertTrue(here.known.any { it.id.startsWith("dashcam.") })
        assertTrue(here.known.any { it.id == "audio.volume" })
        assertEquals(everything.known.count { it.invocation == VehicleInvocation.GenericFeatureSet }, here.missingNames.size)
    }

    // ── A window's percent control may exist by name and not be wired ─────────────────────────────

    @Test
    fun theWindowPercentControlsInsistOnAReadingOnEveryCarButADiLink5() {
        val ids = listOf("window.driver.position", "window.passenger.position", "window.rearLeft.position", "window.rearRight.position")

        assertTrue(on(carThatAgrees(), DiLinkPlatform.DILINK_5).known.none { it.requiresReadback })
        for (platform in listOf(DiLinkPlatform.DILINK_3, DiLinkPlatform.DILINK_4, DiLinkPlatform.UNKNOWN)) {
            val here = on(carThatAgrees(), platform)
            assertEquals(platform.toString(), ids.toSet(), here.known.filter { it.requiresReadback }.map { it.id }.toSet())
        }
    }

    @Test
    fun aControlThatInsistsOnAReadingHasAStateToReadOrIsWithheld() {
        val driver = command("window.driver.position")
        val here = on(carThatAgrees() - driver.stateFeatureName!!, DiLinkPlatform.DILINK_3)

        assertFalse("window.driver.position" in here.known.map { it.id })
        assertEquals(listOf(driver.stateFeatureName), here.missingNames["window.driver.position"])
        // A DiLink 5 car is not asked for the reading, so there the control is offered, sent without a verdict.
        assertNull(on(carThatAgrees() - driver.stateFeatureName!!, DiLinkPlatform.DILINK_5).command("window.driver.position").stateFeatureId)
        for (platform in DiLinkPlatform.entries) {
            for (command in on(carThatAgrees(), platform).known.filter { it.requiresReadback }) assertTrue("${command.id} on $platform", command.stateFeatureId != null)
        }
    }

    // ── What the registry follows ─────────────────────────────────────────────────────────────────

    @Test
    fun theRegistryFollowsTheTableTheHelperReadsAndForgetsItWhenTheTableIsGone() {
        val heating = command("seat.driver.heating")

        VehicleCommandRegistry.useCarTable(carThatAgrees() - heating.featureName!!)
        assertNull(VehicleCommandRegistry.byId("seat.driver.heating"))
        assertEquals(setOf("seat.driver.heating"), VehicleCommandRegistry.unavailable.keys)
        assertEquals(CommandLookup.UnknownSetting::class, VehicleCommandRegistry.resolve("seat", "driver.heating")::class)

        VehicleCommandRegistry.useCarTable(null)
        assertEquals(heating, VehicleCommandRegistry.byId("seat.driver.heating"))
        assertTrue(VehicleCommandRegistry.unavailable.isEmpty())
    }

    @Test
    fun theRegistryUsesTheCarsNumbersForWhatTheHelperDispatches() {
        val driver = command("window.driver")

        VehicleCommandRegistry.useCarTable(carThatAgrees() + (driver.featureName!! to 589299768))

        assertEquals(589299768, VehicleCommandRegistry.byId("window.driver")!!.featureId)
        assertEquals(589299768, VehicleCommandRegistry.known.single { it.id == "window.driver" }.featureId)
    }

    @Test
    fun theAppFollowsWhatTheHelperSaysTheCarCannotDo() {
        VehicleCommandRegistry.useReportedUnavailable(setOf("fridge.mode", "fridge.cool.temperature", "fridge.heat.temperature"))

        assertNull(VehicleCommandRegistry.byId("fridge.mode"))
        assertTrue("fridge" !in VehicleCommandRegistry.functionNames())
        assertEquals(CommandLookup.UnknownFunction, VehicleCommandRegistry.resolve("fridge", "mode"))
        assertTrue(VehicleCommandRegistry.isCommand("fridge.mode"))

        VehicleCommandRegistry.useReportedUnavailable(emptySet())
        assertTrue("fridge" in VehicleCommandRegistry.functionNames())
    }

    @Test
    fun thePlatformTheRegistryIsToldDecidesHowCarefulItIs() {
        VehicleCommandRegistry.platform = DiLinkPlatform.DILINK_3

        assertTrue(VehicleCommandRegistry.known.filter { it.requiresReadback }.isNotEmpty())

        VehicleCommandRegistry.platform = DiLinkPlatform.DILINK_5
        assertTrue(VehicleCommandRegistry.known.none { it.requiresReadback })
    }

    // ── The features the app reads outside the registry ───────────────────────────────────────────

    @Test
    fun aReadOutsideTheRegistryTakesTheCarsOwnNumberForItsName() {
        val name = HeadrestSound.MODE_STATE_NAME

        assertEquals("before the table is read, the number it was verified with", HeadrestSound.MODE_STATE_ID, CarNumbers.of(name, HeadrestSound.MODE_STATE_ID))

        VehicleCommandRegistry.useCarTable(mapOf(name to 93233))
        assertEquals(93233, CarNumbers.of(name, HeadrestSound.MODE_STATE_ID))
        assertNull("the car's table has no such name: no reading", CarNumbers.of("Audio.NOT_A_FEATURE", 1))
    }

    // ── What never changes ────────────────────────────────────────────────────────────────────────

    @Test
    fun noCarEverOffersABlockedDomain() {
        for (table in listOf(null, carThatAgrees(), emptyMap())) {
            for (platform in DiLinkPlatform.entries) {
                assertTrue(VehicleCommandRegistry.commandsFor(platform, table).known.none { it.domain.isBlocked })
            }
        }
    }

    @Test
    fun aCommandThatIsWithheldIsStillKnownToExistAndOneThatNeverExistedIsNot() {
        VehicleCommandRegistry.useReportedUnavailable(setOf("window.driver"))

        assertTrue(VehicleCommandRegistry.isCommand("window.driver"))
        assertTrue(VehicleCommandRegistry.isCommand("fridge.mode"))
        assertFalse(VehicleCommandRegistry.isCommand("engine.start"))
    }

    @Test
    fun noCarSendsTwoCommandsToTheSameControlUnderDifferentNames() {
        val sends = everything.known
            .filter { it.invocation == VehicleInvocation.GenericFeatureSet }
            .groupBy { Triple(it.deviceType, it.featureId, it.overrides.map { o -> o.featureId }) }
        // Fridge cooling and heating share one temperature control by design; nothing else may.
        val shared = sends.filterValues { it.size > 1 }.values.map { group -> group.map { it.id }.toSet() }
        assertTrue("$shared", shared.all { it == setOf("fridge.cool.temperature", "fridge.heat.temperature") })
    }

    @Test
    fun theRegistryFileNoLongerCarriesPerPlatformNumbers() {
        // The car's own table gives the numbers now; the file only says which controls need a reading outside a DiLink 5.
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("vehicle_commands.json")).use { it.readBytes().decodeToString() }

        assertFalse("platforms" in text)
        assertTrue("requiresReadbackElsewhere" in text)
    }
}

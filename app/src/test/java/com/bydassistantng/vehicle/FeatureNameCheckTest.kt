package com.bydassistantng.vehicle

import com.bydassistantng.vehicle.FeatureNameCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureNameCheckTest {
    private val base = VehicleCommandRegistry.commandsFor(DiLinkPlatform.UNKNOWN)

    private fun command(id: String) = checkNotNull(base.known.find { it.id == id }) { "missing command $id" }

    /** A car whose table gives every name the number the app uses for it. */
    private fun carThatAgrees(): Map<String, Int> = FeatureNameCheck.references().associate { it.name to it.number }

    // ── The names in the registry ──────────────────────────────────────────────────────────────

    @Test
    fun everyNumberTheRegistryUsesHasAName() {
        for (command in base.known) {
            val numbers = FeatureNameCheck.numbersUsedBy(command)
            val named = FeatureNameCheck.referencesOf(command).map { it.number }.distinct()
            assertEquals("${command.id}: a number without a name", numbers, named)
        }
    }

    @Test
    fun aNameIsGroupDotNameInTheWayTheCarsTableHasIt() {
        val shape = Regex("""[A-Z][A-Za-z]*\.[A-Za-z0-9_]+""")
        val names = FeatureNameCheck.references().map { it.name }
        assertTrue(names.isNotEmpty())
        for (name in names) assertTrue(name, shape.matches(name))
    }

    @Test
    fun theNameOfADeviceGroupIsTheGroupOfTheDeviceTheCommandUses() {
        val group = mapOf(1000 to "Ac", 1001 to "Bodywork", 1002 to "Audio", 1004 to "Light", 1023 to "Setting")
        for (command in base.known.filter { it.deviceType != null }) {
            for (reference in FeatureNameCheck.referencesOf(command)) {
                assertEquals("${command.id} ${reference.name}", group.getValue(command.deviceType!!), reference.name.substringBefore('.'))
            }
        }
    }

    @Test
    fun aNameStandsForOneNumberWhereverItIsUsed() {
        val references = FeatureNameCheck.references()
        for ((name, uses) in references.groupBy { it.name }) {
            assertEquals("$name has more than one number", 1, uses.map { it.number }.distinct().size)
        }
    }

    @Test
    fun aNumberStandsForOneNameWhereverItIsUsed() {
        val references = FeatureNameCheck.references()
        for ((number, uses) in references.groupBy { it.number }) {
            assertEquals("$number has more than one name", 1, uses.map { it.name }.distinct().size)
        }
    }

    @Test
    fun theCommandsThatUseNoFeatureNumbersHaveNoReferences() {
        for (id in listOf("ac.power", "ac.auto", "dashcam.record", "audio.volume", "screen.rotation")) {
            assertEquals(id, emptyList<FeatureNameCheck.Reference>(), FeatureNameCheck.referencesOf(command(id)))
        }
    }

    @Test
    fun aControlThatNeedsAnotherStateOrWritesAnotherControlNamesThoseToo() {
        val cooling = FeatureNameCheck.referencesOf(command("fridge.cool.temperature"))
        assertEquals(
            setOf(FeatureNameCheck.CONTROL, FeatureNameCheck.STATE, FeatureNameCheck.REQUIRES),
            cooling.map { it.part }.toSet(),
        )
        val massage = FeatureNameCheck.referencesOf(command("seat.driver.massage"))
        assertTrue(massage.any { it.part == FeatureNameCheck.OVERRIDE })
    }

    @Test
    fun aTypedMethodNamesOnlyTheStateItIsConfirmedBy() {
        val references = FeatureNameCheck.referencesOf(command("ambient.color"))

        assertEquals(listOf(FeatureNameCheck.STATE), references.map { it.part })
        assertEquals("Setting.SET_IAL_FRONT_COLOR", references.single().name)
    }

    // ── What is checked ────────────────────────────────────────────────────────────────────────

    @Test
    fun everyCommandOfTheRegistryIsCheckedWithItsOwnNumbersWhateverTheCarRuns() {
        val references = FeatureNameCheck.references()
        val driver = references.first { it.owner == "window.driver.position" && it.part == FeatureNameCheck.CONTROL }

        assertEquals("Bodywork.BODYWORK_LF_WINDOW_TARGET_POSITION_SET", driver.name)
        assertEquals(command("window.driver.position").featureId, driver.number)
        assertTrue(references.map { it.owner }.toSet().containsAll(base.known.filter { FeatureNameCheck.numbersUsedBy(it).isNotEmpty() }.map { it.id }))
    }

    @Test
    fun theFeaturesUsedOutsideTheRegistryAreChecked() {
        val owners = FeatureNameCheck.references().map { it.owner }.toSet()

        assertTrue(owners.containsAll(OtherFeatures.all.map { it.label }))
    }

    // ── The outside features ───────────────────────────────────────────────────────────────────

    @Test
    fun theOutsideFeaturesAreNamedLikeTheRestAndAgreeWithTheRegistryWhereTheyOverlap() {
        val shape = Regex("""[A-Z][A-Za-z]*\.[A-Za-z0-9_]+""")
        for (feature in OtherFeatures.all) assertTrue(feature.name, shape.matches(feature.name))
        assertEquals(OtherFeatures.all.size, OtherFeatures.all.map { it.label }.toSet().size)
        // The headrest sound mode and the exterior speaker's mute state are read by the registry's commands too.
        assertEquals(command("audio.headrestSound").stateFeatureId, OtherFeatures.all.first { it.label == "headrest.mode" }.number)
        assertEquals(command("audio.mediaMute").stateFeatureName, OtherFeatures.all.first { it.label == "exteriorSpeaker.muteState" }.name)
    }

    // ── What the car says ──────────────────────────────────────────────────────────────────────

    @Test
    fun aCarWhoseTableAgreesHasNothingToReport() {
        val results = FeatureNameCheck.check(FeatureNameCheck.references(), carThatAgrees())
        val distinctNames = FeatureNameCheck.references().map { it.name }.toSet().size

        assertTrue(results.all { it.verdict == Verdict.SAME })
        assertEquals("names=$distinctNames/$distinctNames withheld=none", FeatureNameCheck.summary(results))
        assertFalse(FeatureNameCheck.windowsDiffer(results))
    }

    @Test
    fun aNumberTheCarGivesTheNameDifferentlyIsReportedWithTheCarsOwn() {
        val driver = command("window.driver")
        val table = carThatAgrees() + (driver.featureName!! to 589299768)

        val summary = FeatureNameCheck.summary(FeatureNameCheck.check(FeatureNameCheck.references(), table))

        assertTrue(summary, "differ=${driver.featureName}=589299768/${driver.featureId}(window.driver)" in summary)
        assertFalse(summary, "absent=" in summary)
    }

    @Test
    fun aNameTheCarDoesNotHaveIsReportedAbsent() {
        val massage = command("seat.driver.massage")
        val table = carThatAgrees() - massage.featureName!!

        val summary = FeatureNameCheck.summary(FeatureNameCheck.check(FeatureNameCheck.references(), table))

        assertTrue(summary, "absent=${massage.featureName}(seat.driver.massage)" in summary)
        assertFalse(summary, "differ=" in summary)
    }

    @Test
    fun aNameSharedByTwoCommandsIsCountedOnce() {
        // The fridge's cooling and heating set-points are one control.
        val shared = command("fridge.cool.temperature").featureName!!
        assertEquals(shared, command("fridge.heat.temperature").featureName)

        val results = FeatureNameCheck.check(FeatureNameCheck.references(), carThatAgrees() - shared)
        val entries = FeatureNameCheck.summary(results).substringAfter("absent=").split(',').filter { it.startsWith(shared) }

        assertEquals(1, entries.size)
    }

    @Test
    fun theCommandsTheCarCannotDoAreListedByIdAndNotAsNames() {
        val summary = FeatureNameCheck.summary(FeatureNameCheck.check(FeatureNameCheck.references(), carThatAgrees()), listOf("seat.driver.massage", "fridge.mode"))

        assertTrue(summary, summary.endsWith(" withheld=fridge.mode,seat.driver.massage"))
    }

    @Test
    fun theWindowFeaturesAreWorthListingWhenAWindowNameDiffers() {
        val driver = command("window.driver")
        val results = FeatureNameCheck.check(FeatureNameCheck.references(), carThatAgrees() + (driver.featureName!! to 1))

        assertTrue(FeatureNameCheck.windowsDiffer(results))
    }

    @Test
    fun aCarThatDiffersEverywhereStillGivesOneBoundedLine() {
        val table = FeatureNameCheck.references().associate { it.name to it.number + 1 }

        val summary = FeatureNameCheck.summary(FeatureNameCheck.check(FeatureNameCheck.references(), table))

        assertEquals(1, summary.lines().size)
        assertTrue(summary, summary.startsWith("names=0/"))
        assertNotNull(summary)
    }
}

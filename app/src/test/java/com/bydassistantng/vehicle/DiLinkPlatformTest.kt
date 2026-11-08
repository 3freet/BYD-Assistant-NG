package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiLinkPlatformTest {
    private val dilink3 = PlatformSignature.canaries.map { it.dilink3 }
    private val dilink4And5 = PlatformSignature.canaries.map { it.dilink4And5 }

    @Test
    fun theCarsOwnNumbersTellDiLink3FromTheRest() {
        assertEquals(DiLinkPlatform.DILINK_3, PlatformDetector.classify(29, dilink3))
        assertEquals(FeatureNumbering.DILINK_3, PlatformSignature.numbering(dilink3))
    }

    @Test
    fun diLink3IsRecognisedByItsNumbersWhateverTheAndroidLevel() {
        // Neither an Android level nor a build string tells it: the numbers do.
        assertEquals(DiLinkPlatform.DILINK_3, PlatformDetector.classify(28, dilink3))
        assertEquals(DiLinkPlatform.DILINK_3, PlatformDetector.classify(30, dilink3))
    }

    @Test
    fun diLink4And5NumberTheSameWayAndTheAndroidLevelSeparatesThem() {
        assertEquals(DiLinkPlatform.DILINK_4, PlatformDetector.classify(29, dilink4And5))
        assertEquals(DiLinkPlatform.DILINK_5, PlatformDetector.classify(32, dilink4And5))
        assertEquals(DiLinkPlatform.DILINK_5, PlatformDetector.classify(33, dilink4And5))
    }

    @Test
    fun numbersThatAgreeWithNeitherSchemeAreNotRecognised() {
        assertEquals(DiLinkPlatform.UNKNOWN, PlatformDetector.classify(33, listOf(1, 2, 3)))
    }

    @Test
    fun aMixOfTheTwoSchemesIsNotRecognised() {
        val mixed = listOf(dilink3[0], dilink4And5[1], dilink3[2])
        assertEquals(DiLinkPlatform.UNKNOWN, PlatformDetector.classify(29, mixed))
    }

    @Test
    fun aNumberTheCarDoesNotHaveMeansNotRecognised() {
        assertEquals(DiLinkPlatform.UNKNOWN, PlatformDetector.classify(29, listOf(dilink3[0], dilink3[1], null)))
        assertEquals(DiLinkPlatform.UNKNOWN, PlatformDetector.classify(29, listOf(null, null, null)))
    }

    @Test
    fun aPropertyThatCannotBeReadIsEmpty() {
        assertEquals(HeadUnitProperties.KEYS.associateWith { "" }, HeadUnitProperties.readAll())
    }

    @Test
    fun onlyTheVersionPropertiesAreReadNothingThatCouldBeAboutTheOwner() {
        // No VIN, no car name, no region: these four say what the head unit is, and are only ever written to the log.
        assertEquals(listOf("ro.vehicle.type", "ro.byd.dilink.version", "ro.dilink.version", "ro.product.model"), HeadUnitProperties.KEYS)
    }

    @Test
    fun aReadingOfTheWrongLengthIsNotRecognised() {
        assertNull(PlatformSignature.numbering(emptyList()))
        assertNull(PlatformSignature.numbering(dilink3.dropLast(1)))
    }

    @Test
    fun aPhoneOrEmulatorWithoutTheCarsClassesReadsNothingAndIsNotRecognised() {
        assertEquals(listOf<Int?>(null, null, null), PlatformSignature.read())
        assertEquals(DiLinkPlatform.UNKNOWN, PlatformDetector.classify(29, PlatformSignature.read()))
    }

    @Test
    fun whatThePlatformIsKnownAsComesFromTheAppThenFromWhatTheHelperLastReported() {
        assertEquals(DiLinkPlatform.DILINK_5, PlatformDetector.choose(DiLinkPlatform.DILINK_5, DiLinkPlatform.DILINK_3))
        assertEquals(DiLinkPlatform.DILINK_3, PlatformDetector.choose(DiLinkPlatform.UNKNOWN, DiLinkPlatform.DILINK_3))
        assertEquals(DiLinkPlatform.UNKNOWN, PlatformDetector.choose(DiLinkPlatform.UNKNOWN, null))
    }

    @Test
    fun everyPlatformKeyIsFoundAgain() {
        for (platform in DiLinkPlatform.entries) assertEquals(platform, DiLinkPlatform.byKey(platform.key))
        assertNull(DiLinkPlatform.byKey("dilink9"))
        assertNull(DiLinkPlatform.byKey(null))
    }

    @Test
    fun aDiLink30UnitWithTheNewerNumbersIsCalledDiLink30InTheLogAndKeepsWhatTheNumbersSaid() {
        // Seen in a car's log (2026-11-08): ro.vehicle.type Di3.0_3.5UI on Android 10 with the CAN-FD numbers, which the numbers and
        // the Android level alone read as DiLink 4. What the app does follows that reading; only the words in the log change.
        val platform = PlatformDetector.classify(29, dilink4And5)
        assertEquals(DiLinkPlatform.DILINK_4, platform)
        assertEquals("DiLink 3.0 (numbers read as DiLink 4)", PlatformDetector.label(platform, "Di3.0_3.5UI"))
    }

    @Test
    fun theLogNamesTheCarsOwnPlatformWhereItAgreesWithTheNumbers() {
        assertEquals("DiLink 5.1", PlatformDetector.label(DiLinkPlatform.DILINK_5, "Di5.1_5.0UI"))
        assertEquals("DiLink 3.0", PlatformDetector.label(DiLinkPlatform.DILINK_3, "Di3.0_3.0UI"))
        assertEquals("DiLink 3.0 (numbers not recognised)", PlatformDetector.label(DiLinkPlatform.UNKNOWN, "Di3.0_3.5UI"))
    }

    @Test
    fun aPropertyThatIsNotWrittenAsAVersionLeavesTheLabelAsTheNumbersRead() {
        assertEquals("DiLink 4", PlatformDetector.label(DiLinkPlatform.DILINK_4, "DiLink50_5.0UI"))
        assertEquals("DiLink 5", PlatformDetector.label(DiLinkPlatform.DILINK_5, ""))
        assertEquals("DiLink 5", PlatformDetector.label(DiLinkPlatform.DILINK_5, null))
        assertEquals("unrecognised", PlatformDetector.label(DiLinkPlatform.UNKNOWN, "-"))
    }

    @Test
    fun theVehicleTypeIsFoundInTheHelpersLine() {
        val line = "sdk=29 release=10 props=vehicle.type:Di3.0_3.5UI,byd.dilink.version:-,dilink.version:-,product.model:DiLink3.0_For_BYD_AUTO numbers=AC:1"

        assertEquals("Di3.0_3.5UI", PlatformDetector.vehicleTypeIn(line))
        assertNull(PlatformDetector.vehicleTypeIn("props=vehicle.type:-,byd.dilink.version:-"))
        assertNull(PlatformDetector.vehicleTypeIn("no properties here"))
    }

    @Test
    fun theSignatureIsTheNumbersThisRegistryWasVerifiedWith() {
        // The registry's numbers come from a DiLink 5 car; the signature's must agree with the ones it shares.
        val byId = VehicleCommandRegistry.commandsFor(DiLinkPlatform.DILINK_5).known.associateBy { it.id }
        assertEquals(PlatformSignature.canaries.first { it.name == "AC_CTRL_MODE" }.dilink4And5, byId.getValue("ac.auto").featureId)
        assertEquals(PlatformSignature.canaries.first { it.name == "AC_CYCLE_MODE" }.dilink4And5, byId.getValue("ac.airSource").featureId)
        assertEquals(PlatformSignature.canaries.first { it.name == "AC_DEFROST_FRONT_STATE" }.dilink4And5, byId.getValue("ac.frontDefrost").featureId)
    }
}

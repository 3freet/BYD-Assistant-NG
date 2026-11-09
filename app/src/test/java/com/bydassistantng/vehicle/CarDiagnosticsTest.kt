package com.bydassistantng.vehicle

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarDiagnosticsTest {
    @Test
    fun thePropertiesAreWrittenOnePerNameWithoutSpacesAndWithADashWhereNothingIsSet() {
        val line = CarDiagnostics.properties(
            mapOf("ro.vehicle.type" to "Di5.1 Leopard 8", "ro.byd.dilink.version" to "5.1.0.8", "ro.product.model" to "  "),
        )

        assertEquals("vehicle.type:Di5.1_Leopard_8,byd.dilink.version:5.1.0.8,dilink.version:-,product.model:-", line)
    }

    @Test
    fun aVeryLongPropertyIsCutSoTheLineStaysReadable() {
        val line = CarDiagnostics.properties(mapOf("ro.vehicle.type" to "x".repeat(300)))

        assertTrue(line, line.length < 140)
    }

    @Test
    fun theCarsNumbersAreWrittenInTheOrderOfTheSignatureWithAQuestionMarkWhereThereIsNone() {
        val line = CarDiagnostics.numbers(listOf(PlatformSignature.canaries[0].dilink4And5, null, 7))

        assertEquals("AC_CTRL_MODE:${PlatformSignature.canaries[0].dilink4And5},AC_CYCLE_MODE:?,AC_DEFROST_FRONT_STATE:7", line)
    }

    @Test
    fun theClassesListsTheTwoThatTheDriveModeGoesThroughAfterTheOnesTheCommandsUse() {
        val named = CarDiagnostics.classes().split(',').map { it.removePrefix("-") }

        // No car class exists here, so every one is marked missing; what matters is the names and their order.
        assertEquals(listOf("bodywork", "light", "audio", "setting", "ac", "energy", "speed"), named)
        assertTrue(CarDiagnostics.classes().split(',').all { it.startsWith("-") })
    }

    @Test
    fun aSpeedThatWasReadIsWrittenWithOneDecimal() {
        assertEquals("ok:0.0", DriveModeCommand.speedOutcome { 0.0 })
        assertEquals("ok:47.3", DriveModeCommand.speedOutcome { 47.26 })
    }

    @Test
    fun aReadThatGivesNoNumberIsSaidToGiveNone() {
        assertEquals("none", DriveModeCommand.speedOutcome { null })
        assertEquals("none", DriveModeCommand.speedOutcome { "fast" })
    }

    @Test
    fun aRefusalOfTheCarsPermissionCheckNamesThePermissionEvenWhenWrapped() {
        val refusal = SecurityException("Neither user 2000 nor current process has android.permission.BYDAUTO_SPEED_COMMON.")

        assertEquals("refused:BYDAUTO_SPEED_COMMON", DriveModeCommand.speedOutcome { throw InvocationTargetException(refusal) })
    }

    @Test
    fun aClassOrMethodTheCarLacksIsSaidToBeMissing() {
        assertEquals("missing:ClassNotFoundException", DriveModeCommand.speedOutcome { throw ClassNotFoundException("x") })
        assertEquals("missing:NoSuchMethodException", DriveModeCommand.speedOutcome { throw InvocationTargetException(NoSuchMethodException("x")) })
        assertEquals("missing:NoClassDefFoundError", DriveModeCommand.speedOutcome { throw NoClassDefFoundError("x") })
    }

    @Test
    fun anyOtherFailureIsNamedByItsKind() {
        assertEquals("failed:IllegalStateException", DriveModeCommand.speedOutcome { throw IllegalStateException("boom") })
        assertFalse(DriveModeCommand.speedOutcome { throw IllegalStateException("a message that must not reach the log") }.contains("message"))
    }
}

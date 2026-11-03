package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
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
}

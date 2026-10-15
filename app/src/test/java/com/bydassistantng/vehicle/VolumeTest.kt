package com.bydassistantng.vehicle

import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeTest {
    @Test
    fun theMediaVolumeIsReportedWithItsMaximumAndPercent() {
        val report = VolumeTool.report(media = 5, max = 39)
        assertEquals("ok", report.getValue("status").jsonPrimitive.content)
        assertEquals(5, report.getValue("media_volume").jsonPrimitive.intOrNull)
        assertEquals(39, report.getValue("media_volume_max").jsonPrimitive.intOrNull)
        assertEquals(13, report.getValue("media_volume_percent").jsonPrimitive.intOrNull)
    }

    @Test
    fun silentAndFullAreBothReal() {
        assertEquals(0, VolumeTool.report(0, 39).getValue("media_volume_percent").jsonPrimitive.intOrNull)
        assertEquals(100, VolumeTool.report(39, 39).getValue("media_volume_percent").jsonPrimitive.intOrNull)
    }

    @Test
    fun noReadingIsAnErrorNotAGuess() {
        for ((media, max) in listOf<Pair<Int?, Int?>>(null to null, 5 to null, null to 39, 5 to 0, -1 to 39)) {
            assertEquals("error", VolumeTool.report(media, max).getValue("status").jsonPrimitive.content)
        }
    }
}

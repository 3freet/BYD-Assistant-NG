package com.bydassistantng.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

class LogReportTest {
    private val moment: Date = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 31, 14, 3, 22) }.time

    @Test
    fun aSavedFileIsNamedForItsLogAndTheMomentAndSaysWhenItIsMasked() {
        assertEquals("bydassistant-app-log-20261031-140322-masked.txt", LogReport.fileName("app-log", masked = true, now = moment))
        assertEquals("bydassistant-crash-log-20261031-140322.txt", LogReport.fileName("crash-log", masked = false, now = moment))
    }

    @Test
    fun theHeaderCarriesWhatTheVehicleHelperLastReadOfTheCar() {
        val line = "2026-11-03 21:00:00 Vehicle helper found DiLink 3: sdk=29 names=7/76 differ=Bodywork.BODYWORK_LF_WINDOW_CTRL_SET=589299768/1125122104(window.driver) withheld=none"

        val header = LogReport.header(masked = true, carLine = line, now = moment)

        assertTrue(header, "Car software, as the vehicle helper last read it: $line\n" in header)
        // The numbers are the car's own and the header is never masked, so it is what survives however long the log has run.
        assertTrue(header, "589299768/1125122104" in header)
    }

    @Test
    fun theHeaderSaysSoWhenTheVehicleHelperHasNotStartedYet() {
        val header = LogReport.header(masked = false, carLine = null, now = moment)

        assertTrue(header, "the vehicle helper has not started on this firmware yet" in header)
        assertTrue(header, "Private info: NOT hidden" in header)
    }

    @Test
    fun aReportIsTheHeaderThenTheLog() {
        val kept = LogText.Kept("line one\nline two", totalChars = 17)

        assertEquals("HEADER\nline one\nline two", LogReport.text("HEADER\n", kept))
    }

    @Test
    fun aReportSaysWhereOlderLinesWereLeftOut() {
        val kept = LogText.Kept("newest", totalChars = 600)

        val text = LogReport.text("HEADER\n", kept)

        assertTrue(text, text.startsWith("HEADER\n[older lines left out: the last 6 of 600 characters]\nnewest"))
    }
}

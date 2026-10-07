package com.bydassistantng.ui.settings

import androidx.compose.ui.res.stringResource
import com.bydassistantng.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.bydassistantng.util.AppLogger

/** The general diagnostic log — request/response timing, Gemini HTTP status and error bodies,
 * recording stats — as opposed to [CrashLogScreen]'s uncaught-exceptions-only record. This is the
 * one to check for a "slow" or "Gemini returned an error" report, since nothing actually crashed. */
@Composable
fun AppLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LogViewerScreen(
        title = stringResource(R.string.log_app_title),
        onBack = onBack,
        readLog = { AppLogger.readLog(context) },
        onClear = { AppLogger.clear(context) },
    )
}

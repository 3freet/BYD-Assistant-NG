package com.bydassistantng.ui.settings

import androidx.compose.ui.res.stringResource
import com.bydassistantng.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.bydassistantng.util.CrashLogger

@Composable
fun CrashLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LogViewerScreen(
        title = stringResource(R.string.log_crash_title),
        onBack = onBack,
        readLog = { CrashLogger.readLog(context) },
        onClear = { CrashLogger.clear(context) },
    )
}

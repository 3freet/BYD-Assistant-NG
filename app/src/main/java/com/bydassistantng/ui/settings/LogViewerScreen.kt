package com.bydassistantng.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bydassistantng.R
import com.bydassistantng.ui.AppTopBar
import com.bydassistantng.util.AppLogger

/** Shared log-viewer shell used by both [CrashLogScreen] and [AppLogScreen] — same read/clear/share
 * mechanics, different backing log source. Sharing exists so the log text can leave the head unit
 * (email, messaging app, etc.) without adb, since that's normally the only way to get it out. */
@Composable
fun LogViewerScreen(title: String, onBack: () -> Unit, readLog: () -> String, onClear: () -> Unit) {
    val context = LocalContext.current
    var log by remember { mutableStateOf(readLog()) }
    // Only the newest part is shown and shared (see [LogText]).
    val kept = remember(log) { LogText.newest(log) }
    val scroll = rememberScrollState()
    // Starts at the bottom, where the newest lines are.
    LaunchedEffect(kept, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

    Scaffold(
        topBar = { AppTopBar(title, onBack) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onClear()
                    log = readLog()
                }) { Text(stringResource(R.string.action_clear)) }
                OutlinedButton(onClick = {
                    val text = if (kept.isCut) "[older lines left out: the last ${kept.text.length} of ${kept.totalChars} characters]\n${kept.text}" else kept.text
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    // Sharing must never be what crashes the app, whatever the log holds or the unit does.
                    runCatching { context.startActivity(Intent.createChooser(shareIntent, title)) }.onFailure {
                        AppLogger.logError("LogViewer", "Could not share the log", it)
                        Toast.makeText(context, R.string.log_share_failed, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.action_share)) }
            }

            if (kept.isCut) {
                Text(
                    text = stringResource(R.string.log_showing_newest, kept.text.length, kept.totalChars),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = kept.text,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.verticalScroll(scroll),
            )
        }
    }
}

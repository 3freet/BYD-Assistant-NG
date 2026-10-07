package com.bydassistantng.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Shared log-viewer shell used by both [CrashLogScreen] and [AppLogScreen] — same read/clear/share
 * mechanics, different backing log source. Sharing exists so the log text can leave the head unit
 * (email, messaging app, etc.) without adb, since that's normally the only way to get it out. */
@Composable
fun LogViewerScreen(title: String, onBack: () -> Unit, readLog: () -> String, onClear: () -> Unit) {
    val context = LocalContext.current
    var log by remember { mutableStateOf(readLog()) }

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
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, log)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, title))
                }) { Text(stringResource(R.string.action_share)) }
            }

            Text(
                text = log,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
    }
}

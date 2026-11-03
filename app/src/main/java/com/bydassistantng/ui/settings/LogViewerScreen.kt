package com.bydassistantng.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.bydassistantng.R
import com.bydassistantng.ui.AppTopBar
import com.bydassistantng.util.AppLogger
import com.bydassistantng.util.LogMasker
import com.bydassistantng.vehicle.PlatformState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared log-viewer shell used by both [CrashLogScreen] and [AppLogScreen] — same read/clear/share/save
 * mechanics, different backing log source. Sharing and saving exist so the log can leave the head unit
 * (a messaging app, a file manager, a USB stick) without adb, since that's normally the only way to get it out.
 *
 * The log is shown with what is private in it hidden (see [LogMasker]), and that same text is what is shared and saved:
 * what the user sees is what leaves. The switch turns this off for someone who wants the whole log, on the
 * screen and in what they send. [fileLabel] names the saved file. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogViewerScreen(title: String, fileLabel: String, onBack: () -> Unit, readLog: () -> String, onClear: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedTo = stringResource(R.string.log_saved_to)
    var log by remember { mutableStateOf(readLog()) }
    var hidePrivate by rememberSaveable { mutableStateOf(true) }
    // Made off the main thread: a log can be a megabyte, and a head unit is not quick. Null while it is being made.
    val shown by produceState<String?>(initialValue = null, log, hidePrivate) {
        value = null
        value = withContext(Dispatchers.Default) { if (hidePrivate) LogMasker.mask(log) else log }
    }
    // Only the newest part is shown and shared (see [LogText]).
    val kept = remember(shown) { shown?.let { LogText.newest(it) } }
    val scroll = rememberScrollState()
    // Starts at the bottom, where the newest lines are.
    LaunchedEffect(kept, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

    Scaffold(
        topBar = { AppTopBar(title, onBack) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A row that wraps: the labels of some languages do not fit beside each other on a narrow screen.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingButton(label = stringResource(R.string.action_clear), onClick = {
                    onClear()
                    log = readLog()
                })
                SettingButton(label = stringResource(R.string.action_share), enabled = kept != null, onClick = {
                    val text = LogReport.text(LogReport.header(hidePrivate, PlatformState.carLine(context)), kept ?: return@SettingButton)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    // Sharing must never be what crashes the app, whatever the log holds or the unit does.
                    runCatching { context.startActivity(Intent.createChooser(shareIntent, title)) }.onFailure {
                        AppLogger.logError("LogViewer", "Could not share the log", it)
                        Toast.makeText(context, R.string.log_share_failed, Toast.LENGTH_LONG).show()
                    }
                })
                SettingButton(label = stringResource(R.string.action_save_to_downloads), enabled = shown != null, onClick = {
                    val all = shown ?: return@SettingButton
                    val masked = hidePrivate
                    scope.launch {
                        // The whole log, not only the part on the screen.
                        val where = withContext(Dispatchers.IO) {
                            LogReport.saveToDownloads(context, LogReport.fileName(fileLabel, masked), LogReport.header(masked, PlatformState.carLine(context)) + all)
                        }
                        if (where != null) {
                            Toast.makeText(context, savedTo.format(where), Toast.LENGTH_LONG).show()
                        } else {
                            AppLogger.logError("LogViewer", "Could not save the log to Downloads")
                            Toast.makeText(context, R.string.log_save_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }

            SettingsSection(title = "") {
                row {
                    SwitchSetting(
                        title = stringResource(R.string.log_hide_private),
                        subtitle = stringResource(if (hidePrivate) R.string.log_hide_private_on else R.string.log_hide_private_off),
                        checked = hidePrivate,
                        onCheckedChange = { hidePrivate = it },
                    )
                }
            }

            if (kept?.isCut == true) {
                Text(
                    text = stringResource(R.string.log_showing_newest, kept.text.length, kept.totalChars),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = kept?.text ?: stringResource(R.string.log_preparing),
                fontFamily = FontFamily.Monospace,
                // A log is English text and numbers: in a right-to-left language it would otherwise be set from the right
                // and its words and numbers jumbled.
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.verticalScroll(scroll),
            )
        }
    }
}

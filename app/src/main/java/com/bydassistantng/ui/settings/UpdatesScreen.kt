package com.bydassistantng.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydassistantng.R
import com.bydassistantng.ui.AppTopBar
import com.bydassistantng.update.ReleaseNotes
import com.bydassistantng.update.UpdateFailure
import com.bydassistantng.update.UpdateRelease
import com.bydassistantng.update.UpdateState

@Composable
fun UpdatesScreen(onBack: () -> Unit, onOpenAdvanced: () -> Unit, viewModel: UpdatesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val channel by viewModel.channel.collectAsState()
    var confirming by remember { mutableStateOf<UpdateRelease?>(null) }

    LaunchedEffect(Unit) { viewModel.onOpened() }

    confirming?.let { release ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.updates_confirm_title, release.version.toString())) },
            text = { Text(stringResource(R.string.updates_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.install(release)
                    confirming = null
                }) { Text(stringResource(R.string.updates_confirm_install)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.updates_title), onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                Column {
                    Text(stringResource(R.string.updates_installed_version), style = MaterialTheme.typography.labelMedium)
                    Text(viewModel.installed.versionName, style = MaterialTheme.typography.headlineSmall)
                    if (viewModel.supported) {
                        Text(
                            stringResource(R.string.updates_channel_line, channel.label()),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                if (!viewModel.supported) {
                    Text(stringResource(R.string.update_err_unsupported), style = MaterialTheme.typography.bodyMedium)
                } else {
                    StatusBlock(
                        state = state,
                        channelLabel = channel.label(),
                        onCheck = { viewModel.check() },
                        onInstall = { confirming = it },
                        onRetry = { viewModel.install(it) },
                        onCancel = { viewModel.cancelDownload() },
                    )
                }
            }

            val notesFor = when (val current = state) {
                is UpdateState.Available -> current.release
                is UpdateState.Failed -> current.release
                else -> null
            }
            val notes = notesFor?.let { ReleaseNotes.plain(it.notes) }.orEmpty()
            if (notes.isNotEmpty()) {
                item {
                    Column {
                        Text(stringResource(R.string.updates_whats_new), style = MaterialTheme.typography.titleMedium)
                        Text(notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            if (viewModel.supported) {
                item { HorizontalDivider() }
                item {
                    Column {
                        Text(stringResource(R.string.updates_open_advanced), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.view_advanced))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(
    state: UpdateState,
    channelLabel: String,
    onCheck: () -> Unit,
    onInstall: (UpdateRelease) -> Unit,
    onRetry: (UpdateRelease) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    when (state) {
        UpdateState.Idle -> Button(onClick = onCheck) { Text(stringResource(R.string.updates_check)) }

        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.updates_checking), style = MaterialTheme.typography.bodyMedium)
        }

        is UpdateState.UpToDate -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.updates_up_to_date), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.updates_last_checked, formatCheckedAt(context, state.checkedAt)), style = MaterialTheme.typography.bodySmall)
            state.channelLatest?.let {
                Text(stringResource(R.string.updates_channel_latest, channelLabel, it.toString()), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onCheck, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.updates_check)) }
        }

        is UpdateState.Available -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.updates_available_title, state.release.version.toString()), style = MaterialTheme.typography.titleMedium)
            val published = formatPublished(context, state.release.publishedAt)
            val size = formatSize(context, state.release.apkSize)
            if (published.isNotEmpty() && size.isNotEmpty()) {
                Text(stringResource(R.string.updates_available_meta, published, size), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onInstall(state.release) }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.updates_install)) }
        }

        is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.updates_downloading, (state.fraction * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }

        is UpdateState.Installing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.updates_installing), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is UpdateState.AwaitingSystemInstaller ->
            Text(stringResource(R.string.updates_system_installer), style = MaterialTheme.typography.bodyMedium)

        is UpdateState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.failure.userMessage(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.release?.let { release ->
                    Button(onClick = { onRetry(release) }) { Text(stringResource(R.string.updates_retry)) }
                }
                OutlinedButton(onClick = onCheck, enabled = state.failure.kind != UpdateFailure.Kind.UNSUPPORTED) {
                    Text(stringResource(R.string.updates_check))
                }
            }
        }
    }
}

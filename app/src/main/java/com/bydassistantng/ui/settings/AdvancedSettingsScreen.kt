package com.bydassistantng.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydassistantng.R
import com.bydassistantng.ui.AppTopBar
import com.bydassistantng.update.UpdateChannel

/** Settings that most people never need: which releases to follow, and the diagnostic logs. */
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    onOpenCrashLog: () -> Unit,
    onOpenAppLog: () -> Unit,
    viewModel: AdvancedSettingsViewModel = hiltViewModel(),
) {
    val channel by viewModel.channel.collectAsState()
    val autoCheck by viewModel.autoCheck.collectAsState()
    var confirmBeta by remember { mutableStateOf(false) }

    if (confirmBeta) {
        AlertDialog(
            onDismissRequest = { confirmBeta = false },
            title = { Text(stringResource(R.string.advanced_beta_title)) },
            text = { Text(stringResource(R.string.advanced_beta_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setChannel(UpdateChannel.BETA)
                    confirmBeta = false
                }) { Text(stringResource(R.string.action_enable)) }
            },
            dismissButton = { TextButton(onClick = { confirmBeta = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.advanced_title), onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(padding),
        ) {
            if (viewModel.updatesSupported) {
                item {
                    Column {
                        Text(stringResource(R.string.advanced_channel_title), style = MaterialTheme.typography.titleMedium)
                        ChannelOption(
                            title = stringResource(R.string.channel_stable),
                            description = stringResource(R.string.advanced_channel_stable_desc),
                            selected = channel == UpdateChannel.STABLE,
                            onSelect = { viewModel.setChannel(UpdateChannel.STABLE) },
                        )
                        ChannelOption(
                            title = stringResource(R.string.channel_beta),
                            description = stringResource(R.string.advanced_channel_beta_desc),
                            selected = channel == UpdateChannel.BETA,
                            onSelect = { if (channel != UpdateChannel.BETA) confirmBeta = true },
                        )
                        Text(
                            stringResource(R.string.advanced_channel_note),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(stringResource(R.string.advanced_auto_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.advanced_auto_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = autoCheck, onCheckedChange = { viewModel.setAutoCheck(it) })
                    }
                }

                item { HorizontalDivider() }
            }

            item { Text(stringResource(R.string.advanced_diagnostics), style = MaterialTheme.typography.titleMedium) }

            item {
                OutlinedButton(onClick = onOpenAppLog, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.view_app_log)) }
            }

            item {
                OutlinedButton(onClick = onOpenCrashLog, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.view_crash_log)) }
            }
        }
    }
}

@Composable
private fun ChannelOption(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

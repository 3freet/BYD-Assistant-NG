package com.bydassistantng.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydassistantng.R
import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantVoices
import com.bydassistantng.ota.ReleaseInfo
import com.bydassistantng.ui.AppLanguageSelector
import com.bydassistantng.ui.AppTopBar
import com.bydassistantng.ui.AssistantLanguageSelector
import com.bydassistantng.ui.findActivity
import com.bydassistantng.ui.label
import com.bydassistantng.ui.rememberWheelServiceSetup
import com.bydassistantng.util.DilinkAutoStart
import com.bydassistantng.util.WheelKeys

@Composable
fun SettingsScreen(
    pendingOtaRelease: ReleaseInfo?,
    onBack: () -> Unit,
    onOpenCrashLog: () -> Unit,
    onOpenAppLog: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val appLanguage by viewModel.appLanguage.collectAsState()
    val language by viewModel.language.collectAsState()
    val vehicleControlEnabled by viewModel.vehicleControlEnabled.collectAsState()
    val triggerKeyCode by viewModel.triggerKeyCode.collectAsState()
    val bargeInEnabled by viewModel.bargeInEnabled.collectAsState()
    val arabicDialect by viewModel.arabicDialect.collectAsState()
    val assistantVoice by viewModel.assistantVoice.collectAsState()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsState()
    val capturingKey by viewModel.capturingKey.collectAsState()
    val keyMessage by viewModel.keyMessage.collectAsState()
    val wheelSetup = rememberWheelServiceSetup()
    val hasApiKey by viewModel.hasApiKey.collectAsState()
    val apiKeyMessage by viewModel.apiKeyMessage.collectAsState()
    val otaState by viewModel.otaState.collectAsState()
    val autoStartVisitedAt by viewModel.autoStartVisitedAt.collectAsState()

    var apiKeyInput by remember { mutableStateOf("") }
    var showVehicleControlWarning by remember { mutableStateOf(false) }
    var showDialectChoice by remember { mutableStateOf(false) }
    var showVoiceChoice by remember { mutableStateOf(false) }

    LaunchedEffect(pendingOtaRelease) {
        pendingOtaRelease?.let { viewModel.showPendingRelease(it) }
    }

    if (showVehicleControlWarning) {
        AlertDialog(
            onDismissRequest = { showVehicleControlWarning = false },
            title = { Text(stringResource(R.string.vehicle_warning_title)) },
            text = { Text(stringResource(R.string.vehicle_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setVehicleControlEnabled(true)
                    showVehicleControlWarning = false
                }) { Text(stringResource(R.string.action_enable)) }
            },
            dismissButton = { TextButton(onClick = { showVehicleControlWarning = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showDialectChoice) {
        ChoiceDialog(
            title = stringResource(R.string.dialect_title),
            options = ArabicDialect.entries.map { it.name to it.label() },
            selectedKey = arabicDialect.name,
            onSelect = { key ->
                ArabicDialect.entries.find { it.name == key }?.let { viewModel.setArabicDialect(it) }
                showDialectChoice = false
            },
            onDismiss = { showDialectChoice = false },
        )
    }
    if (showVoiceChoice) {
        ChoiceDialog(
            title = stringResource(R.string.voice_title),
            options = listOf("" to stringResource(R.string.voice_default)) + AssistantVoices.all.map { it.name to "${it.name} — ${it.style}" },
            selectedKey = assistantVoice,
            onSelect = { key ->
                viewModel.setAssistantVoice(key)
                showVoiceChoice = false
            },
            onDismiss = { showVoiceChoice = false },
        )
    }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.settings_title), onBack) },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(padding)) {
            item {
                AppLanguageSelector(
                    selected = appLanguage,
                    onSelect = { choice ->
                        if (choice != appLanguage) {
                            viewModel.setAppLanguage(choice)
                            // The screens read the language when they are created, so rebuild this one.
                            context.findActivity()?.recreate()
                        }
                    },
                )
            }

            item { HorizontalDivider() }

            item { AssistantLanguageSelector(selected = language, onSelect = { viewModel.setLanguage(it) }) }

            item {
                ChoiceRow(
                    title = stringResource(R.string.dialect_title),
                    value = arabicDialect.label(),
                    hint = stringResource(R.string.dialect_hint),
                    onClick = { showDialectChoice = true },
                )
            }

            item {
                ChoiceRow(
                    title = stringResource(R.string.voice_title),
                    value = AssistantVoices.all.find { it.name == assistantVoice }?.let { "${it.name} — ${it.style}" } ?: stringResource(R.string.voice_default),
                    hint = stringResource(R.string.voice_hint),
                    onClick = { showVoiceChoice = true },
                )
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(stringResource(R.string.web_search_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.web_search_desc), style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(R.string.web_search_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(checked = webSearchEnabled, onCheckedChange = { viewModel.setWebSearchEnabled(it) })
                }
            }

            item { HorizontalDivider() }

            item {
                Column {
                    Text(stringResource(R.string.wheel_title), style = MaterialTheme.typography.titleMedium)
                    if (!wheelSetup.enabled) {
                        Text(
                            stringResource(R.string.wheel_off),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        OutlinedButton(onClick = { wheelSetup.enable() }, enabled = !wheelSetup.busy) {
                            Text(stringResource(if (wheelSetup.busy) R.string.wheel_turning_on else R.string.wheel_turn_on))
                        }
                        if (wheelSetup.failed) {
                            Text(wheelSetup.failureHint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    } else {
                        Text(
                            if (capturingKey) stringResource(R.string.wheel_press_now)
                            else stringResource(R.string.wheel_on, WheelKeys.describe(context, triggerKeyCode)),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (capturingKey) {
                                OutlinedButton(onClick = { viewModel.cancelKeyCapture() }) { Text(stringResource(R.string.action_cancel)) }
                            } else {
                                OutlinedButton(onClick = { viewModel.startKeyCapture() }) { Text(stringResource(R.string.wheel_use_different)) }
                                OutlinedButton(
                                    onClick = { viewModel.resetKey() },
                                    enabled = triggerKeyCode != WheelKeys.MIC_SHORT_PRESS,
                                ) { Text(stringResource(R.string.wheel_back_to_mic)) }
                            }
                        }
                    }
                    keyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                    Text(
                        stringResource(R.string.wheel_conflict_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(stringResource(R.string.barge_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.barge_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = bargeInEnabled, onCheckedChange = { viewModel.setBargeInEnabled(it) })
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(stringResource(R.string.vehicle_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.vehicle_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = vehicleControlEnabled,
                        onCheckedChange = { checked ->
                            if (checked) showVehicleControlWarning = true else viewModel.setVehicleControlEnabled(false)
                        },
                    )
                }
            }

            // Only meaningful on BYD DiLink firmware, whose own whitelist decides whether this app
            // may start itself after a reboot — see DilinkAutoStart.
            if (DilinkAutoStart.isDilink()) {
                item {
                    val lastUpdateTime = remember {
                        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                    }
                    val visitedSinceUpdate = autoStartVisitedAt > lastUpdateTime
                    Column {
                        Text(stringResource(R.string.autostart_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (visitedSinceUpdate) R.string.autostart_confirmed else R.string.autostart_unconfirmed),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        OutlinedButton(onClick = {
                            DilinkAutoStart.openSettings(context)
                            viewModel.markAutoStartVisited()
                        }) { Text(stringResource(R.string.autostart_open)) }
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                Text(stringResource(R.string.api_key_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (hasApiKey) R.string.api_key_saved else R.string.api_key_none),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text(stringResource(R.string.api_key_new_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.updateApiKey(apiKeyInput)
                        apiKeyInput = ""
                    }) { Text(stringResource(R.string.action_save)) }
                    OutlinedButton(onClick = { viewModel.clearApiKey() }, enabled = hasApiKey) { Text(stringResource(R.string.action_clear)) }
                }
                apiKeyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }

            // No update section unless this build was told where its updates are published.
            if (viewModel.updatesConfigured) {
                item { HorizontalDivider() }

                item {
                    Text(stringResource(R.string.updates_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.updates_current_version, viewModel.currentVersionName),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    OtaSection(otaState, onCheck = { viewModel.checkForUpdate() }, onInstall = { viewModel.installUpdate(it) })
                }
            }

            item { HorizontalDivider() }

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
private fun OtaSection(state: OtaCheckState, onCheck: () -> Unit, onInstall: (ReleaseInfo) -> Unit) {
    when (state) {
        OtaCheckState.Idle -> OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.ota_check)) }
        OtaCheckState.Checking -> Text(stringResource(R.string.ota_checking), style = MaterialTheme.typography.bodySmall)
        OtaCheckState.UpToDate -> Text(stringResource(R.string.ota_up_to_date), style = MaterialTheme.typography.bodySmall)
        is OtaCheckState.Available -> Column {
            Text(stringResource(R.string.ota_available, state.release.tagName), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { onInstall(state.release) }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.ota_download_install)) }
        }
        is OtaCheckState.Downloading -> Column {
            Text(stringResource(R.string.ota_downloading), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        is OtaCheckState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/** A setting whose value is picked from a list in a dialog: the title, the current value, and a hint. */
@Composable
private fun ChoiceRow(title: String, value: String, hint: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(options) { (key, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(key) },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = key == selectedKey, onClick = { onSelect(key) })
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

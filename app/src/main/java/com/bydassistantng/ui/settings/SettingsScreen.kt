package com.bydassistantng.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydassistantng.R
import com.bydassistantng.audio.EndOfSpeechWait
import com.bydassistantng.audio.MusicDuck
import com.bydassistantng.audio.VoiceChannelOptions
import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.data.AssistantVoices
import com.bydassistantng.memory.AddOutcome
import com.bydassistantng.memory.MemoryFact
import com.bydassistantng.memory.MemoryLimits
import com.bydassistantng.ui.AiStudioKeyLink
import com.bydassistantng.ui.findActivity
import com.bydassistantng.ui.label
import com.bydassistantng.ui.rememberCarVoiceAssistantSetup
import com.bydassistantng.ui.rememberWheelServiceSetup
import com.bydassistantng.util.CarVoiceAssistant
import com.bydassistantng.update.UpdateState
import com.bydassistantng.util.AppLanguageChoice
import com.bydassistantng.util.DilinkAutoStart
import com.bydassistantng.util.WheelKeys
import com.bydassistantng.ui.AppAlertDialog

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val appLanguage by viewModel.appLanguage.collectAsState()
    val language by viewModel.language.collectAsState()
    val vehicleControlEnabled by viewModel.vehicleControlEnabled.collectAsState()
    val triggerKeyCode by viewModel.triggerKeyCode.collectAsState()
    val bargeInEnabled by viewModel.bargeInEnabled.collectAsState()
    val endOfSpeechWaitMs by viewModel.endOfSpeechWaitMs.collectAsState()
    val shortTermMemory by viewModel.shortTermMemoryEnabled.collectAsState()
    val longTermMemory by viewModel.longTermMemoryEnabled.collectAsState()
    val memories by viewModel.memories.collectAsState()
    val arabicDialect by viewModel.arabicDialect.collectAsState()
    val assistantVoice by viewModel.assistantVoice.collectAsState()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsState()
    val capturingKey by viewModel.capturingKey.collectAsState()
    val keyMessage by viewModel.keyMessage.collectAsState()
    val wheelSetup = rememberWheelServiceSetup()
    val carVoice = rememberCarVoiceAssistantSetup()
    val hasApiKey by viewModel.hasApiKey.collectAsState()
    val apiKeyMessage by viewModel.apiKeyMessage.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val autoStartVisitedAt by viewModel.autoStartVisitedAt.collectAsState()
    val voiceChannelEnabled by viewModel.voiceChannelEnabled.collectAsState()
    val voiceChannelMusic by viewModel.voiceChannelMusic.collectAsState()
    val voiceChannelAdaptive by viewModel.voiceChannelAdaptive.collectAsState()
    val voiceChannelGain by viewModel.voiceChannelGain.collectAsState()
    val voiceChannelDip by viewModel.voiceChannelDip.collectAsState()

    var showMemories by remember { mutableStateOf(false) }
    var showCarVoiceConfirm by remember { mutableStateOf(false) }
    var showVehicleControlWarning by remember { mutableStateOf(false) }
    var showAppLanguageChoice by remember { mutableStateOf(false) }
    var showLanguageChoice by remember { mutableStateOf(false) }
    var showDialectChoice by remember { mutableStateOf(false) }
    var showVoiceChoice by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    var showMusicChoice by remember { mutableStateOf(false) }

    if (showMusicChoice) {
        ChoiceDialog(
            title = stringResource(R.string.voice_channel_music_title),
            options = MusicDuck.entries.map { ChoiceOption(it.key, it.label(), it.description()) },
            selectedKey = voiceChannelMusic.key,
            onSelect = { key ->
                viewModel.setVoiceChannelMusic(MusicDuck.from(key))
                showMusicChoice = false
            },
            onDismiss = { showMusicChoice = false },
        )
    }
    if (showCarVoiceConfirm) {
        AppAlertDialog(
            onDismissRequest = { showCarVoiceConfirm = false },
            title = { Text(stringResource(R.string.car_voice_confirm_title)) },
            text = { Text(stringResource(R.string.car_voice_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showCarVoiceConfirm = false
                    carVoice.set(on = false)
                }) { Text(stringResource(R.string.car_voice_turn_off)) }
            },
            dismissButton = { TextButton(onClick = { showCarVoiceConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showVehicleControlWarning) {
        AppAlertDialog(
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
    if (showAppLanguageChoice) {
        ChoiceDialog(
            title = stringResource(R.string.app_language_title),
            options = listOf(
                ChoiceOption(AppLanguageChoice.SYSTEM.name, stringResource(R.string.app_language_system)),
                ChoiceOption(AppLanguageChoice.ENGLISH.name, stringResource(R.string.app_language_english)),
                ChoiceOption(AppLanguageChoice.ARABIC.name, stringResource(R.string.app_language_arabic)),
            ),
            selectedKey = appLanguage.name,
            onSelect = { key ->
                showAppLanguageChoice = false
                AppLanguageChoice.entries.find { it.name == key }?.takeIf { it != appLanguage }?.let { choice ->
                    viewModel.setAppLanguage(choice)
                    // The screens read the language when they are created, so rebuild this one.
                    context.findActivity()?.recreate()
                }
            },
            onDismiss = { showAppLanguageChoice = false },
        )
    }
    if (showLanguageChoice) {
        ChoiceDialog(
            title = stringResource(R.string.assistant_language_title),
            options = AssistantLanguage.entries.map { ChoiceOption(it.name, it.label()) },
            selectedKey = language.name,
            onSelect = { key ->
                AssistantLanguage.entries.find { it.name == key }?.let { viewModel.setLanguage(it) }
                showLanguageChoice = false
            },
            onDismiss = { showLanguageChoice = false },
        )
    }
    if (showDialectChoice) {
        ChoiceDialog(
            title = stringResource(R.string.dialect_title),
            options = ArabicDialect.entries.map { ChoiceOption(it.name, it.label()) },
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
            options = listOf(ChoiceOption("", stringResource(R.string.voice_default))) +
                AssistantVoices.all.map { ChoiceOption(it.name, it.name, it.style) },
            selectedKey = assistantVoice,
            onSelect = { key ->
                viewModel.setAssistantVoice(key)
                showVoiceChoice = false
            },
            onDismiss = { showVoiceChoice = false },
        )
    }
    if (showApiKey) {
        ApiKeyDialog(
            hasKey = hasApiKey,
            message = apiKeyMessage,
            onSave = { viewModel.updateApiKey(it) },
            onClear = { viewModel.clearApiKey() },
            onDismiss = { showApiKey = false },
        )
    }
    if (showMemories) {
        MemoryDialog(
            facts = memories,
            onAdd = { text, onResult -> viewModel.addMemory(text, onResult) },
            onDelete = { viewModel.deleteMemory(it) },
            onClearAll = { viewModel.clearMemories() },
            onDismiss = { showMemories = false },
        )
    }

    SettingsPage(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        left = {
            SettingsSection(stringResource(R.string.settings_section_assistant)) {
                row {
                    ChoiceSetting(
                        title = stringResource(R.string.assistant_language_title),
                        subtitle = stringResource(R.string.assistant_language_hint),
                        value = language.label(),
                        onClick = { showLanguageChoice = true },
                    )
                }
                row {
                    ChoiceSetting(
                        title = stringResource(R.string.dialect_title),
                        subtitle = stringResource(R.string.dialect_hint),
                        value = arabicDialect.label(),
                        onClick = { showDialectChoice = true },
                    )
                }
                row {
                    ChoiceSetting(
                        title = stringResource(R.string.voice_title),
                        subtitle = stringResource(R.string.voice_hint),
                        value = if (assistantVoice.isBlank()) stringResource(R.string.voice_default) else assistantVoice,
                        onClick = { showVoiceChoice = true },
                    )
                }
                row {
                    SwitchSetting(
                        title = stringResource(R.string.web_search_title),
                        subtitle = stringResource(R.string.web_search_desc),
                        note = stringResource(R.string.web_search_note),
                        checked = webSearchEnabled,
                        onCheckedChange = { viewModel.setWebSearchEnabled(it) },
                    )
                }
            }

            SettingsSection(stringResource(R.string.settings_section_conversation)) {
                row {
                    SwitchSetting(
                        title = stringResource(R.string.barge_title),
                        subtitle = stringResource(R.string.barge_desc),
                        checked = bargeInEnabled,
                        onCheckedChange = { viewModel.setBargeInEnabled(it) },
                    )
                }
                row {
                    // The value follows the finger while dragging and is stored when it is let go.
                    var dragging by remember(endOfSpeechWaitMs) { mutableFloatStateOf(endOfSpeechWaitMs.toFloat()) }
                    SettingRow(
                        title = stringResource(R.string.end_wait_title),
                        subtitle = stringResource(R.string.end_wait_desc),
                        below = {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Slider(
                                    value = dragging,
                                    onValueChange = { dragging = it },
                                    onValueChangeFinished = { viewModel.setEndOfSpeechWaitMs(EndOfSpeechWait.clamp(dragging.toInt())) },
                                    valueRange = EndOfSpeechWait.MIN_MS.toFloat()..EndOfSpeechWait.MAX_MS.toFloat(),
                                    steps = (EndOfSpeechWait.MAX_MS - EndOfSpeechWait.MIN_MS) / EndOfSpeechWait.STEP_MS - 1,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    stringResource(R.string.end_wait_value, EndOfSpeechWait.clamp(dragging.toInt())),
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                            if (endOfSpeechWaitMs != EndOfSpeechWait.DEFAULT_MS) {
                                TextButton(onClick = { viewModel.setEndOfSpeechWaitMs(EndOfSpeechWait.DEFAULT_MS) }) {
                                    Text(stringResource(R.string.end_wait_reset, EndOfSpeechWait.DEFAULT_MS))
                                }
                            }
                        },
                    )
                }
            }

            // The voice channel: replies on the car's own voice stream, with the music dealt with around them. Only on
            // a car that has the stream.
            if (viewModel.voiceChannelSupported) {
                SettingsSection(stringResource(R.string.settings_section_voice_channel)) {
                    row {
                        SwitchSetting(
                            title = stringResource(R.string.voice_channel_title),
                            subtitle = stringResource(R.string.voice_channel_desc),
                            checked = voiceChannelEnabled,
                            onCheckedChange = { viewModel.setVoiceChannelEnabled(it) },
                        )
                    }
                    // Nothing more about it is shown while it is off.
                    if (voiceChannelEnabled) {
                        row {
                            ChoiceSetting(
                                title = stringResource(R.string.voice_channel_music_title),
                                subtitle = stringResource(R.string.voice_channel_music_desc),
                                value = voiceChannelMusic.label(),
                                onClick = { showMusicChoice = true },
                            )
                        }
                        // How far the music is turned down, for the choice that turns it down by a share.
                        if (voiceChannelMusic == MusicDuck.LEVEL) {
                            row {
                                var dragging by remember(voiceChannelDip) { mutableFloatStateOf(voiceChannelDip.toFloat()) }
                                SettingRow(
                                    title = stringResource(R.string.voice_channel_dip_title),
                                    subtitle = stringResource(R.string.voice_channel_dip_desc),
                                    below = {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            Slider(
                                                value = dragging,
                                                onValueChange = { dragging = it },
                                                onValueChangeFinished = { viewModel.setVoiceChannelDip(VoiceChannelOptions.clampDip(dragging.toInt())) },
                                                valueRange = VoiceChannelOptions.DIP_RANGE.first.toFloat()..VoiceChannelOptions.DIP_RANGE.last.toFloat(),
                                                steps = (VoiceChannelOptions.DIP_RANGE.last - VoiceChannelOptions.DIP_RANGE.first) / VoiceChannelOptions.DIP_STEP - 1,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                stringResource(R.string.voice_channel_dip_value, VoiceChannelOptions.clampDip(dragging.toInt())),
                                                fontSize = 16.sp,
                                                modifier = Modifier.padding(start = 16.dp),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        row {
                            SwitchSetting(
                                title = stringResource(R.string.voice_channel_adaptive_title),
                                subtitle = stringResource(R.string.voice_channel_adaptive_desc),
                                checked = voiceChannelAdaptive,
                                onCheckedChange = { viewModel.setVoiceChannelAdaptive(it) },
                            )
                        }
                        if (voiceChannelAdaptive) {
                            row {
                                // The value follows the finger while dragging and is stored when it is let go.
                                var dragging by remember(voiceChannelGain) { mutableFloatStateOf(voiceChannelGain.toFloat()) }
                                SettingRow(
                                    title = stringResource(R.string.voice_channel_gain_title),
                                    subtitle = stringResource(R.string.voice_channel_gain_desc),
                                    below = {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            Slider(
                                                value = dragging,
                                                onValueChange = { dragging = it },
                                                onValueChangeFinished = { viewModel.setVoiceChannelGain(VoiceChannelOptions.clampGain(dragging.toInt())) },
                                                valueRange = VoiceChannelOptions.GAIN_RANGE.first.toFloat()..VoiceChannelOptions.GAIN_RANGE.last.toFloat(),
                                                steps = VoiceChannelOptions.GAIN_RANGE.last - VoiceChannelOptions.GAIN_RANGE.first - 1,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                stringResource(R.string.voice_channel_gain_value, VoiceChannelOptions.clampGain(dragging.toInt())),
                                                fontSize = 16.sp,
                                                modifier = Modifier.padding(start = 16.dp),
                                            )
                                        }
                                        if (voiceChannelGain != 0) {
                                            TextButton(onClick = { viewModel.setVoiceChannelGain(0) }) {
                                                Text(stringResource(R.string.voice_channel_gain_reset))
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_section_memory)) {
                row {
                    SwitchSetting(
                        title = stringResource(R.string.memory_short_title),
                        subtitle = stringResource(R.string.memory_short_desc),
                        checked = shortTermMemory,
                        onCheckedChange = { viewModel.setShortTermMemoryEnabled(it) },
                    )
                }
                row {
                    SwitchSetting(
                        title = stringResource(R.string.memory_long_title),
                        subtitle = stringResource(R.string.memory_long_desc),
                        checked = longTermMemory,
                        onCheckedChange = { viewModel.setLongTermMemoryEnabled(it) },
                    )
                }
                row {
                    SettingRow(
                        title = stringResource(R.string.memory_manage, memories.size),
                        onClick = { showMemories = true },
                        trailing = { SettingButton(label = stringResource(R.string.action_manage), onClick = { showMemories = true }) },
                    )
                }
            }
        },
        right = {
            SettingsSection(stringResource(R.string.settings_section_car)) {
                row {
                    SwitchSetting(
                        title = stringResource(R.string.vehicle_title),
                        subtitle = stringResource(R.string.vehicle_desc),
                        checked = vehicleControlEnabled,
                        // Switching it back on says what it does first; switching it off needs no question.
                        onCheckedChange = { on -> if (on) showVehicleControlWarning = true else viewModel.setVehicleControlEnabled(false) },
                    )
                }
                row {
                    val subtitle = when {
                        !wheelSetup.enabled -> stringResource(R.string.wheel_off)
                        capturingKey -> stringResource(R.string.wheel_press_now)
                        else -> stringResource(R.string.wheel_on, WheelKeys.describe(context, triggerKeyCode))
                    }
                    SettingRow(
                        title = stringResource(R.string.wheel_title),
                        subtitle = subtitle,
                        trailing = if (!wheelSetup.enabled) {
                            {
                                SettingButton(
                                    label = stringResource(if (wheelSetup.busy) R.string.wheel_turning_on else R.string.wheel_turn_on),
                                    primary = true,
                                    enabled = !wheelSetup.busy,
                                    onClick = { wheelSetup.enable() },
                                )
                            }
                        } else {
                            null
                        },
                        below = {
                            if (!wheelSetup.enabled && wheelSetup.failed) {
                                Text(wheelSetup.failureHint, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                            }
                            if (wheelSetup.enabled) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (capturingKey) {
                                        SettingButton(label = stringResource(R.string.action_cancel), onClick = { viewModel.cancelKeyCapture() })
                                    } else {
                                        SettingButton(label = stringResource(R.string.wheel_use_different), onClick = { viewModel.startKeyCapture() })
                                        SettingButton(
                                            label = stringResource(R.string.wheel_back_to_mic),
                                            enabled = triggerKeyCode != WheelKeys.MIC_SHORT_PRESS,
                                            onClick = { viewModel.resetKey() },
                                        )
                                    }
                                }
                            }
                            keyMessage?.let {
                                Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                            }
                            Text(
                                stringResource(R.string.wheel_conflict_note),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        },
                    )
                }
                // The head unit's own assistant answers the same button, so both can start at once: the user can
                // switch it off here (and back on). Shown only on a unit that has it.
                if (carVoice.state != CarVoiceAssistant.State.ABSENT) {
                    row {
                        val on = carVoice.state == CarVoiceAssistant.State.ON
                        SettingRow(
                            title = stringResource(R.string.car_voice_title),
                            subtitle = stringResource(if (on) R.string.car_voice_on else R.string.car_voice_off),
                            trailing = {
                                SettingButton(
                                    label = stringResource(
                                        when {
                                            carVoice.busy -> R.string.car_voice_working
                                            on -> R.string.car_voice_turn_off
                                            else -> R.string.car_voice_turn_on
                                        },
                                    ),
                                    primary = on,
                                    enabled = !carVoice.busy,
                                    onClick = { if (on) showCarVoiceConfirm = true else carVoice.set(on = true) },
                                )
                            },
                            below = {
                                if (carVoice.failed) {
                                    Text(stringResource(R.string.car_voice_failed), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                                }
                            },
                        )
                    }
                }
                // Only meaningful on BYD DiLink firmware, whose own whitelist decides whether this app
                // may start itself after a reboot — see DilinkAutoStart.
                if (DilinkAutoStart.isDilink()) {
                    row {
                        val lastUpdateTime = remember {
                            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                        }
                        val visitedSinceUpdate = autoStartVisitedAt > lastUpdateTime
                        val open = {
                            DilinkAutoStart.openSettings(context)
                            viewModel.markAutoStartVisited()
                        }
                        SettingRow(
                            title = stringResource(R.string.autostart_title),
                            subtitle = stringResource(if (visitedSinceUpdate) R.string.autostart_confirmed else R.string.autostart_unconfirmed),
                            trailing = { SettingButton(label = stringResource(R.string.action_open), primary = !visitedSinceUpdate, onClick = open) },
                        )
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_section_gemini)) {
                row {
                    val open = {
                        viewModel.clearApiKeyMessage()
                        showApiKey = true
                    }
                    SettingRow(
                        title = stringResource(R.string.api_key_title),
                        subtitle = stringResource(if (hasApiKey) R.string.api_key_saved else R.string.api_key_none),
                        onClick = open,
                        trailing = {
                            SettingButton(
                                label = stringResource(if (hasApiKey) R.string.action_change else R.string.action_add),
                                primary = !hasApiKey,
                                onClick = open,
                            )
                        },
                    )
                }
            }

            SettingsSection(stringResource(R.string.settings_section_app)) {
                row {
                    ChoiceSetting(
                        title = stringResource(R.string.app_language_title),
                        subtitle = stringResource(R.string.app_language_hint),
                        value = when (appLanguage) {
                            AppLanguageChoice.SYSTEM -> stringResource(R.string.app_language_system)
                            AppLanguageChoice.ENGLISH -> stringResource(R.string.app_language_english)
                            AppLanguageChoice.ARABIC -> stringResource(R.string.app_language_arabic)
                        },
                        onClick = { showAppLanguageChoice = true },
                    )
                }
                // Builds that can't update themselves (a local developer build) have nothing to show here.
                if (viewModel.updatesSupported) {
                    row {
                        val available = updateState is UpdateState.Available
                        SettingRow(
                            title = stringResource(R.string.updates_title),
                            subtitle = updateStatusLine(updateState, viewModel.installedVersionName),
                            onClick = onOpenUpdates,
                            trailing = {
                                SettingButton(
                                    label = stringResource(if (available) R.string.updates_banner_view else R.string.action_open),
                                    primary = available,
                                    onClick = onOpenUpdates,
                                )
                            },
                        )
                    }
                }
                row {
                    SettingRow(
                        title = stringResource(R.string.view_advanced),
                        subtitle = stringResource(R.string.settings_advanced_desc),
                        onClick = onOpenAdvanced,
                        trailing = { SettingButton(label = stringResource(R.string.action_open), onClick = onOpenAdvanced) },
                    )
                }
                row {
                    SettingRow(
                        title = stringResource(R.string.view_about),
                        onClick = onOpenAbout,
                        trailing = { SettingButton(label = stringResource(R.string.action_open), onClick = onOpenAbout) },
                    )
                }
            }
        },
    )
}

/** One line on the state of updates, for the Settings row; the Updates screen has the detail. */
@Composable
private fun updateStatusLine(state: UpdateState, installedVersion: String): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.updates_status_unchecked)
    UpdateState.Checking -> stringResource(R.string.updates_status_checking)
    is UpdateState.UpToDate -> stringResource(R.string.updates_status_up_to_date, installedVersion)
    is UpdateState.Available -> stringResource(R.string.updates_status_available, state.release.version.toString())
    is UpdateState.Downloading -> stringResource(R.string.updates_status_downloading, state.release.version.toString())
    is UpdateState.Installing -> stringResource(R.string.updates_status_installing, state.release.version.toString())
    is UpdateState.AwaitingSystemInstaller -> stringResource(R.string.updates_status_installing, state.release.version.toString())
    is UpdateState.Failed -> stringResource(R.string.updates_status_failed)
}

/** Where the Gemini API key is entered: a field, Save, Clear (when one is saved), and where to get one. */
@Composable
private fun ApiKeyDialog(
    hasKey: Boolean,
    message: String?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.api_key_title)) },
        text = {
            Column {
                Text(
                    stringResource(if (hasKey) R.string.api_key_saved else R.string.api_key_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.api_key_new_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                AiStudioKeyLink(modifier = Modifier.padding(top = 8.dp))
                if (hasKey) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = {
                    onSave(input)
                    input = ""
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** What the assistant remembers, with a way to take things out, add a note by typing, and clear the lot. */
@Composable
private fun MemoryDialog(
    facts: List<MemoryFact>,
    onAdd: (String, (AddOutcome) -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val full = stringResource(R.string.memory_full, MemoryLimits.MAX_FACTS)
    val duplicate = stringResource(R.string.memory_duplicate)

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_dialog_title)) },
        text = {
            Column {
                if (facts.isEmpty()) {
                    Text(stringResource(R.string.memory_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(facts, key = { it.id }) { fact ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(fact.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                TextButton(onClick = { onDelete(fact.id) }) { Text(stringResource(R.string.memory_forget)) }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MemoryLimits.MAX_FACT_CHARS) },
                    label = { Text(stringResource(R.string.memory_add_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = draft.isNotBlank(),
                        onClick = {
                            onAdd(draft) { outcome ->
                                when (outcome) {
                                    is AddOutcome.Added -> { draft = ""; note = null }
                                    is AddOutcome.AlreadyKnown -> note = duplicate
                                    AddOutcome.Full -> note = full
                                    AddOutcome.Empty -> Unit
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.memory_add)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_close)) } },
        dismissButton = {
            if (facts.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.memory_clear_all)) }
            }
        },
    )

    if (confirmClear) {
        AppAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.memory_clear_title)) },
            text = { Text(stringResource(R.string.memory_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    confirmClear = false
                }) { Text(stringResource(R.string.memory_clear_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun MusicDuck.label(): String = stringResource(
    when (this) {
        MusicDuck.KEEP -> R.string.voice_music_keep
        MusicDuck.LOWER -> R.string.voice_music_lower
        MusicDuck.LEVEL -> R.string.voice_music_level
        MusicDuck.PAUSE -> R.string.voice_music_pause
    },
)

@Composable
private fun MusicDuck.description(): String = stringResource(
    when (this) {
        MusicDuck.KEEP -> R.string.voice_music_keep_desc
        MusicDuck.LOWER -> R.string.voice_music_lower_desc
        MusicDuck.LEVEL -> R.string.voice_music_level_desc
        MusicDuck.PAUSE -> R.string.voice_music_pause_desc
    },
)

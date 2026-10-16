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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydassistantng.R
import com.bydassistantng.audio.EndOfSpeechWait
import com.bydassistantng.audio.MusicDuck
import com.bydassistantng.audio.VoiceChannelOptions
import com.bydassistantng.audio.VoiceSpeaker
import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.data.AssistantVoices
import com.bydassistantng.memory.AddOutcome
import com.bydassistantng.memory.MemoryFact
import com.bydassistantng.memory.MemoryLimits
import com.bydassistantng.ui.AiStudioKeyLink
import com.bydassistantng.ui.AppAlertDialog
import com.bydassistantng.ui.label

/*
 * The pages of the settings that are about the assistant itself: who it is, how a conversation goes, what it keeps,
 * and how it sounds. Each page owns the dialogs its rows open.
 */

/** The Gemini key, the language and the voice, and web search. */
@Composable
internal fun AssistantPane(viewModel: SettingsViewModel) {
    val language by viewModel.language.collectAsState()
    val arabicDialect by viewModel.arabicDialect.collectAsState()
    val assistantVoice by viewModel.assistantVoice.collectAsState()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()
    val apiKeyMessage by viewModel.apiKeyMessage.collectAsState()
    var showLanguageChoice by remember { mutableStateOf(false) }
    var showDialectChoice by remember { mutableStateOf(false) }
    var showVoiceChoice by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }

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

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsSection(stringResource(R.string.group_setup)) {
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusChip(
                                stringResource(if (hasApiKey) R.string.chip_saved else R.string.chip_missing),
                                if (hasApiKey) ChipKind.OK else ChipKind.ATTENTION,
                            )
                            SettingButton(
                                label = stringResource(if (hasApiKey) R.string.action_change else R.string.action_add),
                                primary = !hasApiKey,
                                onClick = open,
                            )
                        }
                    },
                )
            }
        }
        SettingsSection(stringResource(R.string.group_language_voice)) {
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
        }
        SettingsSection(stringResource(R.string.group_lookup)) {
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
    }
}

/** Talking over the assistant, and how long it waits before it takes your turn as finished. */
@Composable
internal fun ConversationPane(viewModel: SettingsViewModel) {
    val bargeInEnabled by viewModel.bargeInEnabled.collectAsState()
    val waitMs by viewModel.endOfSpeechWaitMs.collectAsState()

    SettingsSection("") {
        row {
            SwitchSetting(
                title = stringResource(R.string.barge_title),
                subtitle = stringResource(R.string.barge_desc),
                checked = bargeInEnabled,
                onCheckedChange = { viewModel.setBargeInEnabled(it) },
            )
        }
        row {
            StepperSetting(
                title = stringResource(R.string.end_wait_title),
                subtitle = stringResource(R.string.end_wait_desc),
                valueText = stringResource(R.string.end_wait_value, waitMs),
                fraction = (waitMs - EndOfSpeechWait.MIN_MS).toFloat() / (EndOfSpeechWait.MAX_MS - EndOfSpeechWait.MIN_MS),
                onLess = if (waitMs > EndOfSpeechWait.MIN_MS) {
                    { viewModel.setEndOfSpeechWaitMs(EndOfSpeechWait.clamp(waitMs - EndOfSpeechWait.STEP_MS)) }
                } else {
                    null
                },
                onMore = if (waitMs < EndOfSpeechWait.MAX_MS) {
                    { viewModel.setEndOfSpeechWaitMs(EndOfSpeechWait.clamp(waitMs + EndOfSpeechWait.STEP_MS)) }
                } else {
                    null
                },
                resetLabel = stringResource(R.string.end_wait_reset, EndOfSpeechWait.DEFAULT_MS).takeIf { waitMs != EndOfSpeechWait.DEFAULT_MS },
                onReset = { viewModel.setEndOfSpeechWaitMs(EndOfSpeechWait.DEFAULT_MS) },
            )
        }
    }
}

/** The two memories, and what the long-term one holds. */
@Composable
internal fun MemoryPane(viewModel: SettingsViewModel) {
    val shortTermMemory by viewModel.shortTermMemoryEnabled.collectAsState()
    val longTermMemory by viewModel.longTermMemoryEnabled.collectAsState()
    val memories by viewModel.memories.collectAsState()
    var showMemories by remember { mutableStateOf(false) }

    if (showMemories) {
        MemoryDialog(
            facts = memories,
            onAdd = { text, onResult -> viewModel.addMemory(text, onResult) },
            onDelete = { viewModel.deleteMemory(it) },
            onClearAll = { viewModel.clearMemories() },
            onDismiss = { showMemories = false },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsSection("") {
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
        }
        SettingsSection("") {
            row {
                SettingRow(
                    title = stringResource(R.string.memory_manage, memories.size),
                    onClick = { showMemories = true },
                    trailing = { SettingButton(label = stringResource(R.string.action_manage), onClick = { showMemories = true }) },
                )
            }
        }
    }
}

/**
 * The assistant's voice on the car's voice channel, and the music while it listens and speaks. Only on a car that
 * has the channel (see [SettingsViewModel.voiceChannelSupported]).
 */
@Composable
internal fun SoundPane(viewModel: SettingsViewModel) {
    val enabled by viewModel.voiceChannelEnabled.collectAsState()
    val music by viewModel.voiceChannelMusic.collectAsState()
    val adaptive by viewModel.voiceChannelAdaptive.collectAsState()
    val gain by viewModel.voiceChannelGain.collectAsState()
    val dip by viewModel.voiceChannelDip.collectAsState()
    val speaker by viewModel.voiceChannelSpeaker.collectAsState()
    val hasHeadrest by viewModel.headrestSpeakerPresent.collectAsState()
    LaunchedEffect(Unit) { viewModel.findHeadrestSpeaker() }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsSection(stringResource(R.string.settings_section_voice_channel)) {
            row {
                SwitchSetting(
                    title = stringResource(R.string.voice_channel_title),
                    subtitle = stringResource(R.string.voice_channel_desc),
                    checked = enabled,
                    onCheckedChange = { viewModel.setVoiceChannelEnabled(it) },
                )
            }
            // Nothing more about it is shown while it is off.
            if (enabled) {
                // Only a car with a headrest speaker has a choice of where the voice is heard.
                if (hasHeadrest) {
                    row(nested = true) {
                        SettingRow(
                            title = stringResource(R.string.voice_speaker_title),
                            subtitle = stringResource(R.string.voice_speaker_desc),
                            below = {
                                SegmentedControl(
                                    options = VoiceSpeaker.entries.map { it.label() },
                                    selected = VoiceSpeaker.entries.indexOf(speaker),
                                    onSelect = { viewModel.setVoiceChannelSpeaker(VoiceSpeaker.entries[it]) },
                                )
                                Text(
                                    stringResource(if (speaker == VoiceSpeaker.HEADREST) R.string.voice_speaker_headrest_note else R.string.voice_speaker_car_note),
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 10.dp),
                                )
                            },
                        )
                    }
                }
                row(nested = true) {
                    SettingRow(
                        title = stringResource(R.string.voice_channel_music_title),
                        subtitle = stringResource(R.string.voice_channel_music_desc),
                        below = {
                            SegmentedControl(
                                options = MusicDuck.entries.map { it.label() },
                                selected = MusicDuck.entries.indexOf(music),
                                onSelect = { viewModel.setVoiceChannelMusic(MusicDuck.entries[it]) },
                            )
                            Text(
                                music.description(),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        },
                    )
                }
                // How far the music is turned down, for the choice that turns it down by a share.
                if (music == MusicDuck.LEVEL) {
                    row(nested = true) {
                        StepperSetting(
                            title = stringResource(R.string.voice_channel_dip_title),
                            subtitle = stringResource(R.string.voice_channel_dip_desc),
                            valueText = stringResource(R.string.voice_channel_dip_value, dip),
                            fraction = (dip - VoiceChannelOptions.DIP_RANGE.first).toFloat() /
                                (VoiceChannelOptions.DIP_RANGE.last - VoiceChannelOptions.DIP_RANGE.first),
                            onLess = if (dip > VoiceChannelOptions.DIP_RANGE.first) {
                                { viewModel.setVoiceChannelDip(dip - VoiceChannelOptions.DIP_STEP) }
                            } else {
                                null
                            },
                            onMore = if (dip < VoiceChannelOptions.DIP_RANGE.last) {
                                { viewModel.setVoiceChannelDip(dip + VoiceChannelOptions.DIP_STEP) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        if (enabled) {
            SettingsSection(stringResource(R.string.group_volume)) {
                row {
                    SwitchSetting(
                        title = stringResource(R.string.voice_channel_adaptive_title),
                        subtitle = stringResource(R.string.voice_channel_adaptive_desc),
                        checked = adaptive,
                        onCheckedChange = { viewModel.setVoiceChannelAdaptive(it) },
                    )
                }
                if (adaptive) {
                    row(nested = true) {
                        StepperSetting(
                            title = stringResource(R.string.voice_channel_gain_title),
                            subtitle = stringResource(R.string.voice_channel_gain_desc),
                            valueText = stringResource(R.string.voice_channel_gain_value, gain),
                            fraction = (gain - VoiceChannelOptions.GAIN_RANGE.first).toFloat() /
                                (VoiceChannelOptions.GAIN_RANGE.last - VoiceChannelOptions.GAIN_RANGE.first),
                            onLess = if (gain > VoiceChannelOptions.GAIN_RANGE.first) {
                                { viewModel.setVoiceChannelGain(gain - 1) }
                            } else {
                                null
                            },
                            onMore = if (gain < VoiceChannelOptions.GAIN_RANGE.last) {
                                { viewModel.setVoiceChannelGain(gain + 1) }
                            } else {
                                null
                            },
                            resetLabel = stringResource(R.string.voice_channel_gain_reset).takeIf { gain != 0 },
                            onReset = { viewModel.setVoiceChannelGain(0) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceSpeaker.label(): String = stringResource(
    when (this) {
        VoiceSpeaker.CAR -> R.string.voice_speaker_car
        VoiceSpeaker.HEADREST -> R.string.voice_speaker_headrest
    },
)

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

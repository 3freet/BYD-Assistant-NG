package com.bydassistantng.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydassistantng.R
import com.bydassistantng.ui.AppTopBar
import com.bydassistantng.ui.label
import com.bydassistantng.ui.rememberCarVoiceAssistantSetup
import com.bydassistantng.ui.rememberWheelServiceSetup
import com.bydassistantng.update.UpdateState
import com.bydassistantng.util.CarVoiceAssistant
import com.bydassistantng.util.DilinkAutoStart

/** Below this width the list of groups and the group beside it become two pages. */
private val SPLIT_FROM = 840.dp

private val RAIL_WIDTH = 300.dp

/**
 * The settings: a list of groups on the left (Assistant, Conversation, Memory, Sound, Car, App), each with a line on
 * how it stands now, and the chosen group's settings on the right; a search box over all of them. On a narrow screen
 * the list is a page of its own and each group opens as another, one step back from the list.
 *
 * @param category the open group; null on a narrow screen means the list, and on a wide one the first group.
 */
@Composable
fun SettingsScreen(
    category: SettingsCategory?,
    onCategoryChange: (SettingsCategory?) -> Unit,
    onBack: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAppLog: () -> Unit,
    onOpenCrashLog: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val wheelSetup = rememberWheelServiceSetup()
    val carVoice = rememberCarVoiceAssistantSetup()
    val categories = SettingsCategory.entries.filter { it != SettingsCategory.SOUND || viewModel.voiceChannelSupported }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()
    val focusManager = LocalFocusManager.current

    val language by viewModel.language.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()
    val bargeIn by viewModel.bargeInEnabled.collectAsState()
    val memories by viewModel.memories.collectAsState()
    val voiceChannelEnabled by viewModel.voiceChannelEnabled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val autoStartConfirmed = autoStartConfirmed(viewModel)

    // What each group needs from the user, for the dot beside it and the line under its name.
    val needsAttention = buildMap {
        put(SettingsCategory.ASSISTANT, if (hasApiKey) 0 else 1)
        put(
            SettingsCategory.CAR,
            (if (wheelSetup.enabled) 0 else 1) + (if (DilinkAutoStart.isDilink() && !autoStartConfirmed) 1 else 0),
        )
        put(SettingsCategory.APP, if (updateState is UpdateState.Available) 1 else 0)
    }
    val statusOf: @Composable (SettingsCategory) -> String = { cat ->
        when (cat) {
            SettingsCategory.ASSISTANT ->
                if (hasApiKey) stringResource(R.string.status_assistant, language.label()) else stringResource(R.string.status_no_key)
            SettingsCategory.CONVERSATION -> stringResource(if (bargeIn) R.string.status_talk_over_on else R.string.status_talk_over_off)
            SettingsCategory.MEMORY -> stringResource(R.string.status_memories, memories.size)
            SettingsCategory.SOUND -> stringResource(if (voiceChannelEnabled) R.string.status_voice_channel_on else R.string.status_voice_channel_off)
            SettingsCategory.CAR -> {
                if ((needsAttention[cat] ?: 0) > 0) stringResource(R.string.status_needs_attention) else stringResource(R.string.status_all_set)
            }
            SettingsCategory.APP ->
                if (updateState is UpdateState.Available) {
                    stringResource(R.string.status_update_available)
                } else {
                    stringResource(R.string.status_version, viewModel.installedVersionName)
                }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BoxWithConstraints {
                val wide = maxWidth >= SPLIT_FROM
                val inDetail = !wide && category != null && !searching
                AppTopBar(
                    title = stringResource(if (inDetail) category!!.title else R.string.settings_title),
                    onBack = {
                        when {
                            searching -> query = ""
                            inDetail -> onCategoryChange(null)
                            else -> onBack()
                        }
                    },
                    trailing = {
                        SearchField(query = query, onQueryChange = { query = it }, modifier = Modifier.width(if (wide) 360.dp else 200.dp))
                    },
                )
            }
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= SPLIT_FROM
            val open = category ?: categories.first().takeIf { wide }
            // One step back at a time: out of the search, then out of a group to the list, then out of the settings.
            BackHandler(enabled = searching || (!wide && category != null)) {
                if (searching) query = "" else onCategoryChange(null)
            }

            val pane: @Composable (SettingsCategory) -> Unit = { cat ->
                when (cat) {
                    SettingsCategory.ASSISTANT -> AssistantPane(viewModel)
                    SettingsCategory.CONVERSATION -> ConversationPane(viewModel)
                    SettingsCategory.MEMORY -> MemoryPane(viewModel)
                    SettingsCategory.SOUND -> SoundPane(viewModel)
                    SettingsCategory.CAR -> CarPane(viewModel, wheelSetup, carVoice)
                    SettingsCategory.APP -> AppPane(viewModel, onOpenUpdates, onOpenAbout, onOpenAppLog, onOpenCrashLog)
                }
            }
            val results: @Composable () -> Unit = {
                SearchResults(
                    query = query,
                    entries = searchIndex(viewModel, carVoice.state != CarVoiceAssistant.State.ABSENT),
                    onPick = { cat ->
                        query = ""
                        // The search is done with: the keyboard goes, so the page it led to is in view.
                        focusManager.clearFocus()
                        onCategoryChange(cat)
                    },
                )
            }
            val rail: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    categories.forEach { cat ->
                        CategoryItem(
                            category = cat,
                            status = statusOf(cat),
                            selected = wide && cat == open && !searching,
                            attention = (needsAttention[cat] ?: 0) > 0,
                            showArrow = !wide,
                            onClick = {
                                query = ""
                                focusManager.clearFocus()
                                onCategoryChange(cat)
                            },
                        )
                    }
                }
            }

            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    rail(Modifier.width(RAIL_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 8.dp, end = 24.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        if (searching) {
                            results()
                        } else {
                            open?.let {
                                PaneHeader(it)
                                pane(it)
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    when {
                        searching -> results()
                        category == null -> rail(Modifier)
                        else -> pane(category)
                    }
                }
            }
        }
    }
}

/** The title of the open group, with a line on what it is for. */
@Composable
private fun PaneHeader(category: SettingsCategory) {
    Column(modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
        Text(stringResource(category.title), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Text(stringResource(category.summary), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/** One group in the list: its icon, its name, how it stands, and a dot when it needs something. */
@Composable
private fun CategoryItem(category: SettingsCategory, status: String, selected: Boolean, attention: Boolean, showArrow: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) colors.primary else colors.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                category.icon,
                contentDescription = null,
                tint = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(category.title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Text(status, fontSize = 14.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
        if (attention) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(AttentionColor))
        } else if (showArrow) {
            // On a narrow screen the list is the way in; the arrow says each entry opens a page.
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

/** The search box of the top bar: a pill with a magnifier, and a cross once something is typed. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 17.sp),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier.height(48.dp),
        decorationBox = { inner ->
            Row(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(colors.surfaceContainer).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.onSurfaceVariant)
                Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    if (query.isEmpty()) Text(stringResource(R.string.settings_search_hint), fontSize = 17.sp, color = colors.onSurfaceVariant)
                    inner()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.settings_search_clear), tint = colors.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

/** Every setting the search can find, for the settings this device actually has. */
@Composable
private fun searchIndex(viewModel: SettingsViewModel, hasCarVoice: Boolean): List<SearchEntry> = buildList {
    fun add(category: SettingsCategory, @StringRes title: Int) = add(SearchEntry(category, title))
    add(SettingsCategory.ASSISTANT, R.string.api_key_title)
    add(SettingsCategory.ASSISTANT, R.string.assistant_language_title)
    add(SettingsCategory.ASSISTANT, R.string.dialect_title)
    add(SettingsCategory.ASSISTANT, R.string.voice_title)
    add(SettingsCategory.ASSISTANT, R.string.web_search_title)
    add(SettingsCategory.ASSISTANT, R.string.greeting_title)
    // Its other settings are only shown while it is on.
    if (viewModel.greetingEnabled.collectAsState().value) {
        if (viewModel.headrestSpeakerPresent.collectAsState().value) add(SettingsCategory.ASSISTANT, R.string.greeting_speaker_title)
        add(SettingsCategory.ASSISTANT, R.string.greeting_test_title)
    }
    add(SettingsCategory.CONVERSATION, R.string.barge_title)
    add(SettingsCategory.CONVERSATION, R.string.end_wait_title)
    add(SettingsCategory.MEMORY, R.string.memory_short_title)
    add(SettingsCategory.MEMORY, R.string.memory_long_title)
    add(SettingsCategory.MEMORY, R.string.memory_dialog_title)
    if (viewModel.voiceChannelSupported) {
        add(SettingsCategory.SOUND, R.string.voice_channel_title)
        if (viewModel.headrestSpeakerPresent.collectAsState().value) add(SettingsCategory.SOUND, R.string.voice_speaker_title)
        add(SettingsCategory.SOUND, R.string.voice_channel_music_title)
        add(SettingsCategory.SOUND, R.string.voice_channel_adaptive_title)
        add(SettingsCategory.SOUND, R.string.voice_channel_gain_title)
    }
    add(SettingsCategory.CAR, R.string.vehicle_title)
    add(SettingsCategory.CAR, R.string.wheel_title)
    if (hasCarVoice) add(SettingsCategory.CAR, R.string.car_voice_title)
    if (DilinkAutoStart.isDilink()) add(SettingsCategory.CAR, R.string.autostart_title)
    if (viewModel.exteriorSpeakerSupported) add(SettingsCategory.CAR, R.string.advanced_exterior_enable_title)
    if (viewModel.driveModesSupported) add(SettingsCategory.CAR, R.string.advanced_drive_modes_title)
    if (viewModel.clusterSupported) add(SettingsCategory.CAR, R.string.advanced_cluster_enable_title)
    add(SettingsCategory.APP, R.string.app_language_title)
    if (viewModel.updatesSupported) {
        add(SettingsCategory.APP, R.string.updates_title)
        add(SettingsCategory.APP, R.string.advanced_channel_title)
        add(SettingsCategory.APP, R.string.advanced_auto_title)
    }
    add(SettingsCategory.APP, R.string.view_app_log)
    add(SettingsCategory.APP, R.string.view_crash_log)
    add(SettingsCategory.APP, R.string.view_about)
}

/** The settings whose names hold what was typed; touching one opens its group. */
@Composable
private fun SearchResults(query: String, entries: List<SearchEntry>, onPick: (SettingsCategory) -> Unit) {
    val found = entries.map { it to stringResource(it.title) }.filter { (_, name) -> matchesQuery(query, name) }
    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.settings_search_results, found.size),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        if (found.isEmpty()) {
            Text(stringResource(R.string.settings_search_none), fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
        } else {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    found.forEachIndexed { index, (entry, name) ->
                        if (index > 0) Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(entry.category) }.padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(stringResource(entry.category.title), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

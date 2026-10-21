package com.bydassistantng.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bydassistantng.R
import com.bydassistantng.ui.AiStudioKeyLink
import com.bydassistantng.ui.AppAlertDialog
import com.bydassistantng.ui.AppLanguageSelector
import com.bydassistantng.ui.AssistantLanguageSelector
import com.bydassistantng.ui.findActivity
import com.bydassistantng.ui.rememberCarVoiceAssistantSetup
import com.bydassistantng.ui.rememberWheelServiceSetup
import com.bydassistantng.ui.settings.AttentionColor
import com.bydassistantng.ui.settings.ChipKind
import com.bydassistantng.ui.settings.SettingButton
import com.bydassistantng.ui.settings.SettingRow
import com.bydassistantng.ui.settings.SettingsSection
import com.bydassistantng.ui.settings.StatusChip
import com.bydassistantng.util.CarVoiceAssistant
import com.bydassistantng.util.DilinkAutoStart

/** The first-time setup, in the order it is done. */
private enum class SetupStep { LANGUAGE, MICROPHONE, KEY, WHEEL, AUTOSTART, DONE }

/** Below this width the list of steps becomes a row of dots over the step. */
private val SPLIT_FROM = 840.dp

/**
 * The first-time setup: one thing at a time, with a list of the steps beside it (a row of dots on a narrow screen), what
 * is required marked as such, and a summary at the end. The microphone and the Gemini key are required (the assistant
 * cannot work without them) and hold up Next; the steering-wheel button, the car's own assistant and auto-start are
 * recommended and can be skipped (a head unit's Settings app can behave unpredictably, and none is worth trapping the
 * user in the setup for: the on-screen mic button works without the wheel button).
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val language by viewModel.language.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var apiKey by remember { mutableStateOf("") }

    var micGranted by remember { mutableStateOf(isGranted(context, Manifest.permission.RECORD_AUDIO)) }
    var notificationsGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(context, Manifest.permission.POST_NOTIFICATIONS))
    }
    var autoStartOpened by remember { mutableStateOf(false) }
    var confirmCarVoiceOff by remember { mutableStateOf(false) }
    val wheelSetup = rememberWheelServiceSetup()
    val carVoice = rememberCarVoiceAssistantSetup()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        result[Manifest.permission.RECORD_AUDIO]?.let { micGranted = it }
        result[Manifest.permission.POST_NOTIFICATIONS]?.let { notificationsGranted = it }
    }

    // Re-check the mic permission after a trip to the system Settings app, which this screen can't
    // get a direct callback for.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                micGranted = isGranted(context, Manifest.permission.RECORD_AUDIO)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-start only exists on BYD DiLink firmware, which has the screen for it.
    val steps = remember {
        buildList {
            add(SetupStep.LANGUAGE)
            add(SetupStep.MICROPHONE)
            add(SetupStep.KEY)
            add(SetupStep.WHEEL)
            if (DilinkAutoStart.isDilink()) add(SetupStep.AUTOSTART)
            add(SetupStep.DONE)
        }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val current = index.coerceIn(0, steps.lastIndex)
    val step = steps[current]
    // Back is one step back, as in the rest of the app; from the first step it leaves.
    BackHandler(enabled = current > 0) { index = current - 1 }

    val keyAdded = apiKey.isNotBlank()
    val canGoOn = when (step) {
        SetupStep.MICROPHONE -> micGranted
        SetupStep.KEY -> keyAdded
        else -> true
    }
    val canStart = micGranted && keyAdded

    if (confirmCarVoiceOff) {
        AppAlertDialog(
            onDismissRequest = { confirmCarVoiceOff = false },
            title = { Text(stringResource(R.string.car_voice_confirm_title)) },
            text = { Text(stringResource(R.string.car_voice_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCarVoiceOff = false
                    carVoice.set(on = false)
                }) { Text(stringResource(R.string.car_voice_turn_off)) }
            },
            dismissButton = { TextButton(onClick = { confirmCarVoiceOff = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    val stateOf: @Composable (SetupStep) -> String = { s ->
        when (s) {
            SetupStep.LANGUAGE -> stringResource(R.string.setup_state_language)
            SetupStep.MICROPHONE -> stringResource(if (micGranted) R.string.chip_allowed else R.string.setup_required)
            SetupStep.KEY -> stringResource(if (keyAdded) R.string.setup_state_added else R.string.setup_required)
            SetupStep.WHEEL -> stringResource(if (wheelSetup.enabled) R.string.setup_state_on else R.string.setup_recommended)
            SetupStep.AUTOSTART -> stringResource(if (autoStartOpened) R.string.setup_state_opened else R.string.setup_recommended)
            SetupStep.DONE -> stringResource(R.string.setup_state_done)
        }
    }
    val doneOf: (SetupStep) -> Boolean = { s ->
        when (s) {
            SetupStep.LANGUAGE -> current > 0
            SetupStep.MICROPHONE -> micGranted
            SetupStep.KEY -> keyAdded
            SetupStep.WHEEL -> wheelSetup.enabled
            SetupStep.AUTOSTART -> autoStartOpened
            SetupStep.DONE -> false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= SPLIT_FROM
        Row(modifier = Modifier.fillMaxSize()) {
            if (wide) {
                Column(
                    modifier = Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.setup_rail_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                    steps.forEachIndexed { i, s ->
                        StepItem(
                            number = i + 1,
                            name = stringResource(s.nameRes()),
                            state = stateOf(s),
                            current = i == current,
                            done = doneOf(s) && i != current,
                            onClick = { index = i },
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 16.dp)) {
                if (!wide) StepDots(count = steps.size, current = current)
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (step) {
                        SetupStep.LANGUAGE -> {
                            StepHeader(current + 1, steps.size, stringResource(R.string.onb_welcome), null, stringResource(R.string.onb_intro))
                            SettingsSection("") {
                                row {
                                    Box(modifier = Modifier.padding(20.dp)) {
                                        AppLanguageSelector(
                                            selected = appLanguage,
                                            onSelect = { choice ->
                                                if (choice != appLanguage) {
                                                    viewModel.setAppLanguage(choice)
                                                    context.findActivity()?.recreate()
                                                }
                                            },
                                        )
                                    }
                                }
                                row { Box(modifier = Modifier.padding(20.dp)) { AssistantLanguageSelector(selected = language, onSelect = { viewModel.setLanguage(it) }) } }
                            }
                        }
                        SetupStep.MICROPHONE -> {
                            StepHeader(current + 1, steps.size, stringResource(R.string.setup_mic_title), required = true, body = stringResource(R.string.setup_mic_body))
                            SettingsSection("") {
                                row {
                                    SettingRow(
                                        title = stringResource(R.string.setup_mic_row),
                                        subtitle = stringResource(if (micGranted) R.string.setup_mic_granted else R.string.setup_mic_hint),
                                        trailing = {
                                            if (micGranted) {
                                                StatusChip(stringResource(R.string.chip_allowed), ChipKind.OK)
                                            } else {
                                                SettingButton(
                                                    label = stringResource(R.string.setup_mic_button),
                                                    primary = true,
                                                    onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                            SettingsSection(stringResource(R.string.setup_recommended)) {
                                row {
                                    SettingRow(
                                        title = stringResource(R.string.setup_notif_row),
                                        subtitle = stringResource(R.string.setup_notif_desc),
                                        trailing = {
                                            if (notificationsGranted) {
                                                StatusChip(stringResource(R.string.chip_allowed), ChipKind.OK)
                                            } else {
                                                SettingButton(
                                                    label = stringResource(R.string.setup_notif_button),
                                                    onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        SetupStep.KEY -> {
                            StepHeader(current + 1, steps.size, stringResource(R.string.setup_key_title), required = true, body = stringResource(R.string.setup_key_body))
                            SettingsSection("") {
                                row {
                                    Column(modifier = Modifier.padding(20.dp)) {
                                        OutlinedTextField(
                                            value = apiKey,
                                            onValueChange = { apiKey = it },
                                            label = { Text(stringResource(R.string.onb_api_label)) },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            stringResource(R.string.onb_api_note),
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 10.dp),
                                        )
                                        AiStudioKeyLink(modifier = Modifier.padding(top = 12.dp))
                                        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                                    }
                                }
                            }
                        }
                        SetupStep.WHEEL -> {
                            StepHeader(current + 1, steps.size, stringResource(R.string.setup_wheel_title), required = false, body = stringResource(R.string.setup_wheel_body))
                            SettingsSection("") {
                                row {
                                    SettingRow(
                                        title = stringResource(R.string.wheel_title),
                                        subtitle = stringResource(if (wheelSetup.enabled) R.string.setup_wheel_on else R.string.setup_wheel_needs_adb),
                                        trailing = {
                                            if (wheelSetup.enabled) {
                                                StatusChip(stringResource(R.string.setup_state_on), ChipKind.OK)
                                            } else {
                                                SettingButton(
                                                    label = stringResource(if (wheelSetup.busy) R.string.wheel_turning_on else R.string.wheel_turn_on),
                                                    primary = true,
                                                    enabled = !wheelSetup.busy,
                                                    onClick = { wheelSetup.enable() },
                                                )
                                            }
                                        },
                                        below = {
                                            if (!wheelSetup.enabled && wheelSetup.busy) {
                                                Text(stringResource(R.string.wheel_waiting_for_prompt), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (!wheelSetup.enabled && wheelSetup.failed) {
                                                Text(wheelSetup.failureHint, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                                            }
                                        },
                                    )
                                }
                                // The car's own assistant answers the same button, so one press can start both: it can be
                                // switched off here too (and back on in Settings). Only on a unit that has it.
                                if (carVoice.state != CarVoiceAssistant.State.ABSENT) {
                                    row {
                                        val on = carVoice.state == CarVoiceAssistant.State.ON
                                        SettingRow(
                                            title = stringResource(R.string.car_voice_title),
                                            subtitle = stringResource(if (on) R.string.car_voice_on else R.string.car_voice_off),
                                            trailing = {
                                                if (on) {
                                                    SettingButton(
                                                        label = stringResource(if (carVoice.busy) R.string.car_voice_working else R.string.car_voice_turn_off),
                                                        enabled = !carVoice.busy,
                                                        onClick = { confirmCarVoiceOff = true },
                                                    )
                                                } else {
                                                    StatusChip(stringResource(R.string.setup_state_off), ChipKind.OK)
                                                }
                                            },
                                            below = {
                                                if (carVoice.failed) {
                                                    Text(stringResource(R.string.car_voice_failed), fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.setup_wheel_skip_note),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        SetupStep.AUTOSTART -> {
                            StepHeader(current + 1, steps.size, stringResource(R.string.setup_autostart_title), required = false, body = stringResource(R.string.setup_autostart_body))
                            SettingsSection("") {
                                row {
                                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        NumberedLine(1, stringResource(R.string.setup_autostart_step1))
                                        NumberedLine(2, stringResource(R.string.setup_autostart_step2, stringResource(R.string.app_name)))
                                        NumberedLine(3, stringResource(R.string.setup_autostart_step3), emphasis = true)
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SettingButton(
                                    label = stringResource(R.string.autostart_open),
                                    primary = !autoStartOpened,
                                    onClick = {
                                        DilinkAutoStart.openSettings(context)
                                        viewModel.markAutoStartVisited()
                                        autoStartOpened = true
                                    },
                                )
                                if (autoStartOpened) StatusChip(stringResource(R.string.setup_autostart_opened), ChipKind.OK)
                            }
                        }
                        SetupStep.DONE -> {
                            StepHeader(current + 1, steps.size, stringResource(R.string.setup_done_title), null, stringResource(R.string.setup_done_body))
                            SettingsSection("") {
                                row { SummaryLine(micGranted, stringResource(R.string.setup_mic_row), stringResource(if (micGranted) R.string.chip_allowed else R.string.setup_sum_needed)) }
                                row { SummaryLine(keyAdded, stringResource(R.string.setup_key_title), stringResource(if (keyAdded) R.string.setup_state_added else R.string.setup_sum_needed)) }
                                row { SummaryLine(wheelSetup.enabled, stringResource(R.string.wheel_title), stringResource(if (wheelSetup.enabled) R.string.setup_state_on else R.string.setup_sum_wheel_off)) }
                                if (SetupStep.AUTOSTART in steps) {
                                    row {
                                        SummaryLine(
                                            autoStartOpened,
                                            stringResource(R.string.autostart_title),
                                            stringResource(if (autoStartOpened) R.string.setup_sum_autostart_yes else R.string.setup_sum_autostart_no),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // What goes on: Back, Skip (for what can be skipped) and the one thing to press next.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (current > 0) {
                        TextButton(onClick = { index = current - 1 }) { Text(stringResource(R.string.setup_back), fontSize = 17.sp) }
                    }
                    Box(modifier = Modifier.weight(1f))
                    if (step == SetupStep.WHEEL || step == SetupStep.AUTOSTART) {
                        TextButton(onClick = { index = current + 1 }) { Text(stringResource(R.string.setup_skip), fontSize = 17.sp) }
                    }
                    if (step == SetupStep.DONE) {
                        SettingButton(
                            label = stringResource(R.string.onb_get_started),
                            primary = true,
                            enabled = canStart,
                            onClick = { viewModel.saveApiKeyAndFinish(apiKey, onFinished) },
                        )
                    } else {
                        SettingButton(label = stringResource(R.string.setup_next), primary = true, enabled = canGoOn, onClick = { index = current + 1 })
                    }
                }
            }
        }
    }
}

private fun SetupStep.nameRes(): Int = when (this) {
    SetupStep.LANGUAGE -> R.string.setup_name_language
    SetupStep.MICROPHONE -> R.string.setup_name_microphone
    SetupStep.KEY -> R.string.setup_name_key
    SetupStep.WHEEL -> R.string.setup_name_wheel
    SetupStep.AUTOSTART -> R.string.setup_name_autostart
    SetupStep.DONE -> R.string.setup_name_done
}

/** One step in the list: its number (a tick once done), its name and how it stands. */
@Composable
private fun StepItem(number: Int, name: String, state: String, current: Boolean, done: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (current) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    when {
                        current -> colors.primary
                        done -> colors.primary.copy(alpha = 0.2f)
                        else -> colors.surfaceContainer
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            } else {
                Text(
                    number.toString(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (current) colors.onPrimary else colors.onSurfaceVariant,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Text(state, fontSize = 14.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** On a narrow screen: where in the steps this is, as a row of dots. */
@Composable
private fun StepDots(count: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .size(if (i == current) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
        }
    }
}

/** The top of a step: where it is in the list, its title (with Required or Recommended beside it), and what it is for. */
@Composable
private fun StepHeader(number: Int, total: Int, title: String, required: Boolean?, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.setup_step_of, number, total), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            when (required) {
                true -> StatusChip(stringResource(R.string.setup_required), ChipKind.ATTENTION)
                false -> StatusChip(stringResource(R.string.setup_recommended), ChipKind.MUTED)
                null -> Unit
            }
        }
        Text(body, fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
    }
}

/** One of the things to do, with its number; the one that matters most stands out. */
@Composable
private fun NumberedLine(number: Int, text: String, emphasis: Boolean = false) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier.size(30.dp).clip(CircleShape).background(if (emphasis) AttentionColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (emphasis) AttentionColor else MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            color = if (emphasis) AttentionColor else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).heightIn(min = 30.dp),
        )
    }
}

/** A line of the final summary: done (a tick) or not (a warning), what it is, and how it stands. */
@Composable
private fun SummaryLine(ok: Boolean, title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else AttentionColor,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun isGranted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

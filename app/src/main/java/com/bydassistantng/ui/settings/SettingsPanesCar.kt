package com.bydassistantng.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydassistantng.R
import com.bydassistantng.audio.ExteriorVolume
import com.bydassistantng.ui.AppAlertDialog
import com.bydassistantng.ui.CarVoiceAssistantSetup
import com.bydassistantng.ui.WheelServiceSetup
import com.bydassistantng.ui.findActivity
import com.bydassistantng.update.UpdateChannel
import com.bydassistantng.update.UpdateState
import com.bydassistantng.util.AppLanguageChoice
import com.bydassistantng.util.CarVoiceAssistant
import com.bydassistantng.util.DilinkAutoStart
import com.bydassistantng.util.WheelKeys

/*
 * The pages about the car and about the app: what the assistant may operate, the steering-wheel button and the car's own
 * assistant, the experimental car features, and then the app's language, updates and support.
 */

/** Whether the head unit's auto-start screen has been opened since the app was last updated (the app cannot see its answer). */
@Composable
internal fun autoStartConfirmed(viewModel: SettingsViewModel): Boolean {
    val context = LocalContext.current
    val visitedAt by viewModel.autoStartVisitedAt.collectAsState()
    val lastUpdateTime = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    }
    return visitedAt > lastUpdateTime
}

/** Vehicle control, the steering-wheel button, the car's own assistant, auto-start, and the experimental car features. */
@Composable
internal fun CarPane(viewModel: SettingsViewModel, wheelSetup: WheelServiceSetup, carVoice: CarVoiceAssistantSetup) {
    val context = LocalContext.current
    val vehicleControlEnabled by viewModel.vehicleControlEnabled.collectAsState()
    val triggerKeyCode by viewModel.triggerKeyCode.collectAsState()
    val capturingKey by viewModel.capturingKey.collectAsState()
    val keyMessage by viewModel.keyMessage.collectAsState()
    val exteriorEnabled by viewModel.exteriorSpeakerEnabled.collectAsState()
    val exteriorVolume by viewModel.exteriorVolume.collectAsState()
    val driveModesEnabled by viewModel.driveModesEnabled.collectAsState()
    val clusterEnabled by viewModel.clusterScreenEnabled.collectAsState()
    val clusterKeepMapsDisabled by viewModel.clusterKeepMapsDisabled.collectAsState()
    val autoStartConfirmed = autoStartConfirmed(viewModel)
    var showCarVoiceConfirm by remember { mutableStateOf(false) }
    var showVehicleControlWarning by remember { mutableStateOf(false) }
    var showVolumeChoice by remember { mutableStateOf(false) }
    var confirmExterior by remember { mutableStateOf(false) }
    var confirmDriveModes by remember { mutableStateOf(false) }
    var confirmCluster by remember { mutableStateOf(false) }

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
    if (showVolumeChoice) {
        ChoiceDialog(
            title = stringResource(R.string.advanced_exterior_title),
            options = ExteriorVolume.entries.map { ChoiceOption(it.name, it.label()) },
            selectedKey = exteriorVolume.name,
            onSelect = { key ->
                ExteriorVolume.entries.find { it.name == key }?.let { viewModel.setExteriorVolume(it) }
                showVolumeChoice = false
            },
            onDismiss = { showVolumeChoice = false },
        )
    }
    // The experimental features are only switched on after the user has read what that means.
    if (confirmExterior) {
        EnableDialog(
            title = R.string.advanced_exterior_confirm_title,
            body = R.string.advanced_exterior_confirm_body,
            onEnable = { viewModel.setExteriorSpeakerEnabled(true) },
            onDismiss = { confirmExterior = false },
        )
    }
    if (confirmDriveModes) {
        EnableDialog(
            title = R.string.advanced_drive_modes_confirm_title,
            body = R.string.advanced_drive_modes_confirm_body,
            onEnable = { viewModel.setDriveModesEnabled(true) },
            onDismiss = { confirmDriveModes = false },
        )
    }
    if (confirmCluster) {
        EnableDialog(
            title = R.string.advanced_cluster_confirm_title,
            body = R.string.advanced_cluster_confirm_body,
            onEnable = { viewModel.setClusterScreenEnabled(true) },
            onDismiss = { confirmCluster = false },
        )
    }

    val openAutoStart = {
        DilinkAutoStart.openSettings(context)
        viewModel.markAutoStartVisited()
    }
    // Only meaningful on BYD DiLink firmware, whose own whitelist decides whether this app may start itself after a
    // reboot — see DilinkAutoStart.
    val showAutoStart = DilinkAutoStart.isDilink()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (showAutoStart && !autoStartConfirmed) {
            NoticeBanner(
                text = stringResource(R.string.autostart_notice),
                actionLabel = stringResource(R.string.action_open),
                onAction = openAutoStart,
            )
        }

        SettingsSection(stringResource(R.string.group_control)) {
            row {
                SwitchSetting(
                    title = stringResource(R.string.vehicle_title),
                    subtitle = stringResource(R.string.vehicle_desc),
                    checked = vehicleControlEnabled,
                    // Switching it back on says what it does first; switching it off needs no question.
                    onCheckedChange = { on -> if (on) showVehicleControlWarning = true else viewModel.setVehicleControlEnabled(false) },
                )
            }
        }

        SettingsSection(stringResource(R.string.wheel_title)) {
            row {
                val subtitle = when {
                    !wheelSetup.enabled -> stringResource(R.string.wheel_off)
                    capturingKey -> stringResource(R.string.wheel_press_now)
                    else -> stringResource(R.string.wheel_on, WheelKeys.describe(context, triggerKeyCode))
                }
                SettingRow(
                    title = stringResource(R.string.wheel_button_row),
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
            if (showAutoStart) {
                row {
                    SettingRow(
                        title = stringResource(R.string.autostart_title),
                        subtitle = stringResource(if (autoStartConfirmed) R.string.autostart_confirmed else R.string.autostart_unconfirmed),
                        trailing = { SettingButton(label = stringResource(R.string.action_open), primary = !autoStartConfirmed, onClick = openAutoStart) },
                        // Amber until the screen has been opened, and again after an update: the same as the dot on the Car menu.
                        attention = !autoStartConfirmed,
                    )
                }
            }
        }

        // Only in a build and on a car that have the features at all.
        if (viewModel.exteriorSpeakerSupported || viewModel.driveModesSupported || viewModel.clusterSupported) {
            SettingsSection(stringResource(R.string.advanced_experimental_title)) {
                row { SettingNote(stringResource(R.string.advanced_experimental_note)) }
                if (viewModel.exteriorSpeakerSupported) {
                    row {
                        SwitchSetting(
                            title = stringResource(R.string.advanced_exterior_enable_title),
                            subtitle = stringResource(R.string.advanced_exterior_enable_desc),
                            checked = exteriorEnabled,
                            // Switching on goes through the confirmation; switching off needs none.
                            onCheckedChange = { on -> if (on) confirmExterior = true else viewModel.setExteriorSpeakerEnabled(false) },
                        )
                    }
                    // Nothing about the exterior speaker is shown while it is off.
                    if (exteriorEnabled) {
                        row(nested = true) {
                            ChoiceSetting(
                                title = stringResource(R.string.advanced_exterior_title),
                                subtitle = stringResource(R.string.advanced_exterior_desc),
                                value = exteriorVolume.label(),
                                onClick = { showVolumeChoice = true },
                            )
                        }
                    }
                }
                if (viewModel.driveModesSupported) {
                    row {
                        SwitchSetting(
                            title = stringResource(R.string.advanced_drive_modes_title),
                            subtitle = stringResource(R.string.advanced_drive_modes_desc),
                            checked = driveModesEnabled,
                            onCheckedChange = { on -> if (on) confirmDriveModes = true else viewModel.setDriveModesEnabled(false) },
                        )
                    }
                }
                // The instrument cluster: it turns BYD Maps on while an app is on the cluster, and the app is shown in its middle.
                if (viewModel.clusterSupported) {
                    row {
                        SwitchSetting(
                            title = stringResource(R.string.advanced_cluster_enable_title),
                            subtitle = stringResource(R.string.advanced_cluster_enable_desc),
                            checked = clusterEnabled,
                            onCheckedChange = { on -> if (on) confirmCluster = true else viewModel.setClusterScreenEnabled(false) },
                        )
                    }
                    // Nothing more about it is shown while it is off.
                    if (clusterEnabled) {
                        row(nested = true) {
                            SwitchSetting(
                                title = stringResource(R.string.advanced_cluster_keep_title),
                                subtitle = stringResource(R.string.advanced_cluster_keep_desc),
                                checked = clusterKeepMapsDisabled,
                                onCheckedChange = { viewModel.setClusterKeepMapsDisabled(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A confirmation that an experimental feature is wanted, after the user has read what it does. */
@Composable
private fun EnableDialog(title: Int, body: Int, onEnable: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = {
                onEnable()
                onDismiss()
            }) { Text(stringResource(R.string.action_enable)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The app's language, the update options, and the way to the logs and to About. */
@Composable
internal fun AppPane(
    viewModel: SettingsViewModel,
    onOpenUpdates: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAppLog: () -> Unit,
    onOpenCrashLog: () -> Unit,
) {
    val context = LocalContext.current
    val appLanguage by viewModel.appLanguage.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val channel by viewModel.updateChannel.collectAsState()
    val autoCheck by viewModel.autoCheckUpdates.collectAsState()
    var showAppLanguageChoice by remember { mutableStateOf(false) }
    var confirmBeta by remember { mutableStateOf(false) }

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
    if (confirmBeta) {
        AppAlertDialog(
            onDismissRequest = { confirmBeta = false },
            title = { Text(stringResource(R.string.advanced_beta_title)) },
            text = { Text(stringResource(R.string.advanced_beta_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setUpdateChannel(UpdateChannel.BETA)
                    confirmBeta = false
                }) { Text(stringResource(R.string.action_enable)) }
            },
            dismissButton = { TextButton(onClick = { confirmBeta = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsSection(stringResource(R.string.group_language)) {
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
        }

        // Builds that can't update themselves (a local developer build) have nothing to show here.
        if (viewModel.updatesSupported) {
            SettingsSection(stringResource(R.string.advanced_section_updates)) {
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
                row {
                    SettingRow(
                        title = stringResource(R.string.advanced_channel_title),
                        subtitle = stringResource(R.string.advanced_channel_note),
                        below = {
                            SegmentedControl(
                                options = listOf(stringResource(R.string.channel_stable), stringResource(R.string.channel_beta)),
                                selected = if (channel == UpdateChannel.BETA) 1 else 0,
                                onSelect = { index ->
                                    when {
                                        index == 1 && channel != UpdateChannel.BETA -> confirmBeta = true
                                        index == 0 && channel != UpdateChannel.STABLE -> viewModel.setUpdateChannel(UpdateChannel.STABLE)
                                    }
                                },
                            )
                            Text(
                                stringResource(if (channel == UpdateChannel.BETA) R.string.advanced_channel_beta_desc else R.string.advanced_channel_stable_desc),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        },
                    )
                }
                row {
                    SwitchSetting(
                        title = stringResource(R.string.advanced_auto_title),
                        subtitle = stringResource(R.string.advanced_auto_desc),
                        checked = autoCheck,
                        onCheckedChange = { viewModel.setAutoCheckUpdates(it) },
                    )
                }
            }
        }

        SettingsSection(stringResource(R.string.group_support)) {
            row {
                SettingRow(
                    title = stringResource(R.string.view_app_log),
                    onClick = onOpenAppLog,
                    trailing = { SettingButton(label = stringResource(R.string.action_open), onClick = onOpenAppLog) },
                )
            }
            row {
                SettingRow(
                    title = stringResource(R.string.view_crash_log),
                    onClick = onOpenCrashLog,
                    trailing = { SettingButton(label = stringResource(R.string.action_open), onClick = onOpenCrashLog) },
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
    }
}

/** One line on the state of updates, for the Settings row; the Updates screen has the detail. */
@Composable
internal fun updateStatusLine(state: UpdateState, installedVersion: String): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.updates_status_unchecked)
    UpdateState.Checking -> stringResource(R.string.updates_status_checking)
    is UpdateState.UpToDate -> stringResource(R.string.updates_status_up_to_date, installedVersion)
    is UpdateState.Available -> stringResource(R.string.updates_status_available, state.release.version.toString())
    is UpdateState.Downloading -> stringResource(R.string.updates_status_downloading, state.release.version.toString())
    is UpdateState.Installing -> stringResource(R.string.updates_status_installing, state.release.version.toString())
    is UpdateState.AwaitingSystemInstaller -> stringResource(R.string.updates_status_installing, state.release.version.toString())
    is UpdateState.Failed -> stringResource(R.string.updates_status_failed)
}

@Composable
private fun ExteriorVolume.label(): String = stringResource(
    when (this) {
        ExteriorVolume.LOW -> R.string.exterior_volume_low
        ExteriorVolume.MEDIUM -> R.string.exterior_volume_medium
        ExteriorVolume.HIGH -> R.string.exterior_volume_high
    },
)

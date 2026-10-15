package com.bydassistantng.ui.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydassistantng.R
import com.bydassistantng.audio.ExteriorVolume
import com.bydassistantng.update.UpdateChannel
import com.bydassistantng.ui.AppAlertDialog

/**
 * Settings that most people never need: which releases to follow, the experimental car features (each off
 * until switched on, after a confirmation), and the diagnostic logs.
 */
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    onOpenCrashLog: () -> Unit,
    onOpenAppLog: () -> Unit,
    viewModel: AdvancedSettingsViewModel = hiltViewModel(),
) {
    val channel by viewModel.channel.collectAsState()
    val autoCheck by viewModel.autoCheck.collectAsState()
    val exteriorVolume by viewModel.exteriorVolume.collectAsState()
    val exteriorEnabled by viewModel.exteriorSpeakerEnabled.collectAsState()
    val driveModesEnabled by viewModel.driveModesEnabled.collectAsState()
    val clusterEnabled by viewModel.clusterScreenEnabled.collectAsState()
    val clusterKeepMapsDisabled by viewModel.clusterKeepMapsDisabled.collectAsState()
    var showChannelChoice by remember { mutableStateOf(false) }
    var confirmBeta by remember { mutableStateOf(false) }
    var showVolumeChoice by remember { mutableStateOf(false) }
    var confirmExterior by remember { mutableStateOf(false) }
    var confirmDriveModes by remember { mutableStateOf(false) }

    if (showChannelChoice) {
        ChoiceDialog(
            title = stringResource(R.string.advanced_channel_title),
            options = listOf(
                ChoiceOption(UpdateChannel.STABLE.name, stringResource(R.string.channel_stable), stringResource(R.string.advanced_channel_stable_desc)),
                ChoiceOption(UpdateChannel.BETA.name, stringResource(R.string.channel_beta), stringResource(R.string.advanced_channel_beta_desc)),
            ),
            selectedKey = channel.name,
            onSelect = { key ->
                showChannelChoice = false
                when {
                    key == channel.name -> Unit
                    key == UpdateChannel.BETA.name -> confirmBeta = true
                    else -> viewModel.setChannel(UpdateChannel.STABLE)
                }
            },
            onDismiss = { showChannelChoice = false },
        )
    }
    if (confirmBeta) {
        AppAlertDialog(
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
        AppAlertDialog(
            onDismissRequest = { confirmExterior = false },
            title = { Text(stringResource(R.string.advanced_exterior_confirm_title)) },
            text = { Text(stringResource(R.string.advanced_exterior_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setExteriorSpeakerEnabled(true)
                    confirmExterior = false
                }) { Text(stringResource(R.string.action_enable)) }
            },
            dismissButton = { TextButton(onClick = { confirmExterior = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDriveModes) {
        AppAlertDialog(
            onDismissRequest = { confirmDriveModes = false },
            title = { Text(stringResource(R.string.advanced_drive_modes_confirm_title)) },
            text = { Text(stringResource(R.string.advanced_drive_modes_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDriveModesEnabled(true)
                    confirmDriveModes = false
                }) { Text(stringResource(R.string.action_enable)) }
            },
            dismissButton = { TextButton(onClick = { confirmDriveModes = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    SettingsPage(
        title = stringResource(R.string.advanced_title),
        onBack = onBack,
        left = {
            // A local developer build can't update itself, so it has no update options.
            if (viewModel.updatesSupported) {
                SettingsSection(stringResource(R.string.advanced_section_updates)) {
                    row {
                        ChoiceSetting(
                            title = stringResource(R.string.advanced_channel_title),
                            subtitle = stringResource(R.string.advanced_channel_note),
                            value = stringResource(if (channel == UpdateChannel.BETA) R.string.channel_beta else R.string.channel_stable),
                            onClick = { showChannelChoice = true },
                        )
                    }
                    row {
                        SwitchSetting(
                            title = stringResource(R.string.advanced_auto_title),
                            subtitle = stringResource(R.string.advanced_auto_desc),
                            checked = autoCheck,
                            onCheckedChange = { viewModel.setAutoCheck(it) },
                        )
                    }
                }
            }

            SettingsSection(stringResource(R.string.advanced_diagnostics)) {
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
            }
        },
        right = {
            // Only in a build that has the features at all.
            SettingsSection(stringResource(R.string.advanced_experimental_title)) {
                if (viewModel.exteriorSpeakerSupported || viewModel.driveModesSupported) {
                    row { SettingNote(stringResource(R.string.advanced_experimental_note)) }
                }
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
                        row {
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
            }
            // The cluster screen: a development build only. It turns BYD Maps on while an app is on the cluster.
            if (viewModel.clusterSupported) {
                SettingsSection(stringResource(R.string.advanced_cluster_title)) {
                    row {
                        SwitchSetting(
                            title = stringResource(R.string.advanced_cluster_enable_title),
                            subtitle = stringResource(R.string.advanced_cluster_enable_desc),
                            checked = clusterEnabled,
                            onCheckedChange = { viewModel.setClusterScreenEnabled(it) },
                        )
                    }
                    row {
                        SwitchSetting(
                            title = stringResource(R.string.advanced_cluster_keep_title),
                            subtitle = stringResource(R.string.advanced_cluster_keep_desc),
                            checked = clusterKeepMapsDisabled,
                            onCheckedChange = { viewModel.setClusterKeepMapsDisabled(it) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ExteriorVolume.label(): String = stringResource(
    when (this) {
        ExteriorVolume.LOW -> R.string.exterior_volume_low
        ExteriorVolume.MEDIUM -> R.string.exterior_volume_medium
        ExteriorVolume.HIGH -> R.string.exterior_volume_high
    },
)

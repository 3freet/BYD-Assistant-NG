package com.bydassistantng.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydassistantng.R
import com.bydassistantng.update.UpdateState
import com.bydassistantng.util.hasMicPermission

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenUpdates: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val vehicleControlEnabled by viewModel.vehicleControlEnabled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val justUpdatedTo by viewModel.justUpdatedTo.collectAsState()

    // Re-checked on every tap rather than remembered: an "only this time" mic grant can lapse
    // while this screen stays composed.
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onMicTapped()
    }

    Scaffold(
        topBar = {
            // fillMaxWidth, not fillMaxSize: a topBar slot that fills the whole height leaves the
            // content below it with zero height, which hid the mic button and status text entirely.
            Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings))
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (vehicleControlEnabled) {
                    Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.home_vehicle_armed), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 16.dp))
                }

                Text(
                    text = statusText(uiState),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = if (uiState is HomeUiState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                )

                Box(modifier = Modifier.padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    if (uiState is HomeUiState.Connecting || uiState is HomeUiState.Thinking || uiState is HomeUiState.Dispatching) {
                        CircularProgressIndicator(modifier = Modifier.size(96.dp))
                    }
                    FloatingActionButton(
                        onClick = {
                            if (context.hasMicPermission()) viewModel.onMicTapped()
                            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        shape = CircleShape,
                        modifier = Modifier.size(80.dp),
                    ) {
                        Icon(
                            // Stop while a conversation is going (whatever stage), mic when idle.
                            imageVector = if (uiState is HomeUiState.Idle || uiState is HomeUiState.Error) Icons.Filled.Mic else Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.home_talk),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
            UpdateNote(
                availableVersion = (updateState as? UpdateState.Available)?.release?.version?.toString(),
                justUpdatedTo = justUpdatedTo,
                onView = onOpenUpdates,
                onDismissUpdated = { viewModel.dismissJustUpdated() },
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp),
            )
        }
    }
}

/** A quiet strip at the top of Home: a new version waiting, or the one just installed. */
@Composable
private fun UpdateNote(
    availableVersion: String?,
    justUpdatedTo: String?,
    onView: () -> Unit,
    onDismissUpdated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when {
        justUpdatedTo != null -> stringResource(R.string.updates_banner_updated, justUpdatedTo)
        availableVersion != null -> stringResource(R.string.updates_banner_available, availableVersion)
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (justUpdatedTo != null) {
                TextButton(onClick = onDismissUpdated) { Text(stringResource(R.string.action_dismiss)) }
            } else {
                TextButton(onClick = onView) { Text(stringResource(R.string.updates_banner_view)) }
            }
        }
    }
}

@Composable
private fun statusText(state: HomeUiState): String = when (state) {
    HomeUiState.Idle -> stringResource(R.string.home_tap_to_talk)
    HomeUiState.Connecting -> stringResource(R.string.home_connecting)
    HomeUiState.Listening -> stringResource(R.string.home_listening)
    HomeUiState.Thinking -> stringResource(R.string.home_thinking)
    is HomeUiState.Dispatching -> state.displayName
    is HomeUiState.Speaking -> state.text
    is HomeUiState.Error -> state.message
}

package com.bydassistantng.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.bydassistantng.util.hasMicPermission

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val vehicleControlEnabled by viewModel.vehicleControlEnabled.collectAsState()

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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
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

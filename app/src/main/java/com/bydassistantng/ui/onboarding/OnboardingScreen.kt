package com.bydassistantng.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bydassistantng.R
import com.bydassistantng.ui.AiStudioKeyLink
import com.bydassistantng.ui.AppLanguageSelector
import com.bydassistantng.ui.AssistantLanguageSelector
import com.bydassistantng.ui.findActivity
import com.bydassistantng.ui.rememberWheelServiceSetup

/** Mic permission + API key are hard requirements (the app cannot function without them);
 * notifications and the steering-wheel button are offered but not blocking — a head unit's Settings
 * app can behave unpredictably, and neither is worth trapping the user in onboarding for (the
 * on-screen mic button works without the wheel button). */
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
    val wheelSetup = rememberWheelServiceSetup()

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(stringResource(R.string.onb_welcome), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.onb_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item {
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

        item {
            PermissionRow(
                title = stringResource(R.string.onb_mic_title),
                subtitle = stringResource(R.string.onb_mic_desc),
                granted = micGranted,
            ) {
                val toRequest = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(toRequest.toTypedArray())
            }
        }

        item {
            PermissionRow(
                title = stringResource(R.string.onb_notif_title),
                subtitle = stringResource(R.string.onb_notif_desc),
                granted = notificationsGranted,
            ) { permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
        }

        item {
            PermissionRow(
                title = stringResource(R.string.onb_wheel_title),
                subtitle = stringResource(R.string.onb_wheel_desc),
                granted = wheelSetup.enabled,
                note = if (wheelSetup.failed) wheelSetup.failureHint else null,
            ) { wheelSetup.enable() }
        }

        item { AssistantLanguageSelector(selected = language, onSelect = { viewModel.setLanguage(it) }) }

        item {
            Text(stringResource(R.string.onb_api_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.onb_api_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.onb_api_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            AiStudioKeyLink(modifier = Modifier.padding(top = 4.dp))
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
        }

        item {
            Button(
                onClick = { viewModel.saveApiKeyAndFinish(apiKey, onFinished) },
                enabled = micGranted && apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onb_get_started)) }
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, note: String? = null, onRequest: () -> Unit) {
    Column {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (granted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.onb_granted), modifier = Modifier.padding(start = 4.dp))
            }
        } else {
            OutlinedButton(onClick = onRequest, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = null)
                Text(stringResource(R.string.onb_grant), modifier = Modifier.padding(start = 4.dp))
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    }
}

private fun isGranted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

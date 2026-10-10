package com.bydassistantng.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydassistantng.ui.about.AboutScreen
import com.bydassistantng.ui.home.HomeScreen
import com.bydassistantng.ui.onboarding.OnboardingScreen
import com.bydassistantng.ui.settings.AdvancedSettingsScreen
import com.bydassistantng.ui.settings.AppLogScreen
import com.bydassistantng.ui.settings.CrashLogScreen
import com.bydassistantng.ui.settings.SettingsScreen
import com.bydassistantng.ui.settings.UpdatesScreen
import com.bydassistantng.ui.theme.AppTheme
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.hasMicPermission
import dagger.hilt.android.AndroidEntryPoint

/** Set by [com.bydassistantng.service.WheelKeyService] when a button press finds the mic
 * permission revoked: the service can't show a permission dialog itself, so it opens this activity
 * to do it. */
const val EXTRA_REQUEST_MIC_PERMISSION = "EXTRA_REQUEST_MIC_PERMISSION"

/** Set by the "update available" notification, to open straight onto the Updates screen. */
const val EXTRA_OPEN_UPDATES = "EXTRA_OPEN_UPDATES"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Registered as a property so it exists before the activity reaches STARTED, as required.
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // The app language (and, for Arabic, right-to-left layout) is applied to the whole activity here.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window's layout direction comes from the *application* context, whose language was fixed when the
        // process started — so after a language switch it would keep the old direction. Say it outright.
        window.decorView.layoutDirection = TextUtils.getLayoutDirectionFromLocale(AppLanguage.locale(this))
        val openUpdates = intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false)

        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false) && !hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(openUpdates = openUpdates)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(openUpdates: Boolean, viewModel: MainViewModel = hiltViewModel()) {
    val screen by viewModel.screen.collectAsState()
    // Where the Updates screen's back button leads: the Home banner and the notification open it from Home.
    var updatesFrom by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    var openedFromNotification by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openUpdates, screen) {
        if (openUpdates && !openedFromNotification && screen != null && screen != Screen.ONBOARDING) {
            openedFromNotification = true
            updatesFrom = Screen.HOME
            viewModel.navigateTo(Screen.UPDATES)
        }
    }

    when (screen) {
        null -> Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        Screen.ONBOARDING -> OnboardingScreen(onFinished = { viewModel.onOnboardingFinished() })
        Screen.HOME -> HomeScreen(
            onOpenSettings = { viewModel.navigateTo(Screen.SETTINGS) },
            onOpenUpdates = {
                updatesFrom = Screen.HOME
                viewModel.navigateTo(Screen.UPDATES)
            },
        )
        Screen.SETTINGS -> SettingsScreen(
            onBack = { viewModel.navigateTo(Screen.HOME) },
            onOpenUpdates = {
                updatesFrom = Screen.SETTINGS
                viewModel.navigateTo(Screen.UPDATES)
            },
            onOpenAdvanced = { viewModel.navigateTo(Screen.ADVANCED) },
            onOpenAbout = { viewModel.navigateTo(Screen.ABOUT) },
        )
        Screen.ADVANCED -> AdvancedSettingsScreen(
            onBack = { viewModel.navigateTo(Screen.SETTINGS) },
            onOpenCrashLog = { viewModel.navigateTo(Screen.CRASH_LOG) },
            onOpenAppLog = { viewModel.navigateTo(Screen.APP_LOG) },
        )
        Screen.UPDATES -> UpdatesScreen(
            onBack = { viewModel.navigateTo(updatesFrom) },
            onOpenAdvanced = { viewModel.navigateTo(Screen.ADVANCED) },
        )
        Screen.ABOUT -> AboutScreen(onBack = { viewModel.navigateTo(Screen.SETTINGS) })
        Screen.CRASH_LOG -> CrashLogScreen(onBack = { viewModel.navigateTo(Screen.ADVANCED) })
        Screen.APP_LOG -> AppLogScreen(onBack = { viewModel.navigateTo(Screen.ADVANCED) })
    }
}

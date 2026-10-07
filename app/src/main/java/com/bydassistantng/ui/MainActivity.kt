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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydassistantng.ota.EXTRA_OTA_BODY
import com.bydassistantng.ota.EXTRA_OTA_NAME
import com.bydassistantng.ota.EXTRA_OTA_URL
import com.bydassistantng.ota.EXTRA_OTA_VERSION
import com.bydassistantng.ota.ReleaseInfo
import com.bydassistantng.ui.home.HomeScreen
import com.bydassistantng.ui.onboarding.OnboardingScreen
import com.bydassistantng.ui.settings.AppLogScreen
import com.bydassistantng.ui.settings.CrashLogScreen
import com.bydassistantng.ui.settings.SettingsScreen
import com.bydassistantng.ui.theme.AppTheme
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.hasMicPermission
import dagger.hilt.android.AndroidEntryPoint

/** Set by [com.bydassistantng.service.WheelKeyService] when a button press finds the mic
 * permission revoked: the service can't show a permission dialog itself, so it opens this activity
 * to do it. */
const val EXTRA_REQUEST_MIC_PERMISSION = "EXTRA_REQUEST_MIC_PERMISSION"

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
        val pendingOtaRelease = releaseInfoFromIntent(intent)

        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false) && !hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(pendingOtaRelease = pendingOtaRelease)
                }
            }
        }
    }

    /** The OTA update notification opens this activity with these extras (see [OtaUpdater]) —
     * built directly from the notification's own data, no redundant network round-trip needed. */
    private fun releaseInfoFromIntent(intent: Intent): ReleaseInfo? {
        val version = intent.getStringExtra(EXTRA_OTA_VERSION) ?: return null
        val url = intent.getStringExtra(EXTRA_OTA_URL) ?: return null
        val name = intent.getStringExtra(EXTRA_OTA_NAME) ?: return null
        val body = intent.getStringExtra(EXTRA_OTA_BODY) ?: ""
        return ReleaseInfo(
            tagName = version,
            versionName = version.removePrefix("v").removePrefix("V"),
            title = version,
            body = body,
            htmlUrl = "",
            downloadUrl = url,
            apkName = name,
        )
    }
}

@Composable
private fun AppRoot(pendingOtaRelease: ReleaseInfo?, viewModel: MainViewModel = hiltViewModel()) {
    val screen by viewModel.screen.collectAsState()

    LaunchedEffect(pendingOtaRelease, screen) {
        if (pendingOtaRelease != null && screen != null && screen != Screen.SETTINGS) viewModel.navigateTo(Screen.SETTINGS)
    }

    when (screen) {
        null -> Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        Screen.ONBOARDING -> OnboardingScreen(onFinished = { viewModel.onOnboardingFinished() })
        Screen.HOME -> HomeScreen(onOpenSettings = { viewModel.navigateTo(Screen.SETTINGS) })
        Screen.SETTINGS -> SettingsScreen(
            pendingOtaRelease = pendingOtaRelease,
            onBack = { viewModel.navigateTo(Screen.HOME) },
            onOpenCrashLog = { viewModel.navigateTo(Screen.CRASH_LOG) },
            onOpenAppLog = { viewModel.navigateTo(Screen.APP_LOG) },
        )
        Screen.CRASH_LOG -> CrashLogScreen(onBack = { viewModel.navigateTo(Screen.SETTINGS) })
        Screen.APP_LOG -> AppLogScreen(onBack = { viewModel.navigateTo(Screen.SETTINGS) })
    }
}

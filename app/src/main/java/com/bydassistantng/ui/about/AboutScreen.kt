package com.bydassistantng.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bydassistantng.BuildConfig
import com.bydassistantng.R
import com.bydassistantng.ui.AppTopBar

/**
 * Who made this, what it is built from, and — the part that matters most for a car — that using it is the
 * user's own responsibility. Shown from Settings.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = { AppTopBar(stringResource(R.string.about_title), onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item { HorizontalDivider() }

            item {
                Section(R.string.about_disclaimer_title, R.string.about_disclaimer_body)
            }

            item { HorizontalDivider() }

            item {
                Column {
                    Section(R.string.about_open_source_title, R.string.about_open_source_body)
                    // Only when this build was told where the project lives (see assistant.updateRepo).
                    if (BuildConfig.SOURCE_URL.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SOURCE_URL)))
                                } catch (_: ActivityNotFoundException) {
                                    // No browser on this head unit: nothing to open.
                                }
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text(stringResource(R.string.about_source_link)) }
                    }
                }
            }

            item { Section(R.string.about_libraries_title, R.string.about_libraries_body) }
        }
    }
}

@Composable
private fun Section(title: Int, body: Int) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

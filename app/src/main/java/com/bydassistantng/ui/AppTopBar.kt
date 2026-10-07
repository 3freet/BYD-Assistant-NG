package com.bydassistantng.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import com.bydassistantng.R

/**
 * The top bar of every inner screen. In a right-to-left language the text and the rest of the layout
 * mirror, but the back button deliberately does not: it stays on the left, where it is in English, because
 * that is the side the driver reaches for. (It is placed in the bar's trailing slot, which is the left in
 * RTL, and drawn as a left-pointing arrow in both languages.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBack: () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (!rtl) BackButton(onBack, rtl = false) },
        actions = { if (rtl) BackButton(onBack, rtl = true) },
    )
}

@Composable
private fun BackButton(onBack: () -> Unit, rtl: Boolean) {
    IconButton(onClick = onBack) {
        // The "forward" arrow is the one that mirrors into a left-pointing arrow in right-to-left.
        Icon(
            imageVector = if (rtl) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
        )
    }
}

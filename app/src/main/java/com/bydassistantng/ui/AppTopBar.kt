package com.bydassistantng.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bydassistantng.R

/**
 * The top bar of every inner screen: a back arrow and the title, plain on the page. In a right-to-left
 * language the text and the rest of the layout mirror, but the back button deliberately does not: it stays on
 * the left, where it is in English, because that is the side the driver reaches for. (It is placed after the
 * title, which is the left in RTL, and drawn as a left-pointing arrow in both languages.)
 */
@Composable
fun AppTopBar(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!rtl) BackButton(onBack, rtl = false)
        // [trailing] (a search field, say) is at the far end of the title: the right, or the start side in right-to-left.
        if (rtl) trailing?.invoke()
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        if (!rtl) trailing?.invoke()
        if (rtl) BackButton(onBack, rtl = true)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, rtl: Boolean) {
    IconButton(onClick = onBack) {
        // The "forward" arrow is the one that mirrors into a left-pointing arrow in right-to-left.
        Icon(
            imageVector = if (rtl) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

package com.bydassistantng.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.bydassistantng.R
import com.bydassistantng.util.AppLanguage

/** Where a Gemini API key is created. */
const val AI_STUDIO_KEYS_URL = "https://aistudio.google.com/apikey"

/**
 * Opens [url] in a browser. When more than one app can open web links the system's "Open with" list is shown, so
 * the user picks the browser instead of always getting the default one; with a single candidate it opens at once.
 * False when the device has no browser (a head unit may not ship one).
 */
fun openWebPage(context: Context, url: String): Boolean {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.queryIntentActivities(view, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(view, 0)
    }
    val intent = if (candidates.size > 1) Intent.createChooser(view, AppLanguage.string(context, R.string.open_with)) else view
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/**
 * "To generate your Google AI Studio key, click here." — the last words are the link. The sentence and the link
 * text are separate strings so each language can word the sentence its own way; the link is found in it by text.
 * When the device has no browser, the address is shown to be typed in on another device.
 */
@Composable
fun AiStudioKeyLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var browserMissing by remember { mutableStateOf(false) }
    val linkLabel = stringResource(R.string.api_key_get_link)
    val sentence = stringResource(R.string.api_key_get_text, linkLabel)
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val text = buildAnnotatedString {
        append(sentence)
        val start = sentence.indexOf(linkLabel)
        if (start >= 0) {
            addLink(
                LinkAnnotation.Clickable("ai-studio-keys", linkStyle) { browserMissing = !openWebPage(context, AI_STUDIO_KEYS_URL) },
                start,
                start + linkLabel.length,
            )
        }
    }
    Column(modifier) {
        Text(text, style = MaterialTheme.typography.bodySmall)
        if (browserMissing) {
            Text(
                stringResource(R.string.api_key_open_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

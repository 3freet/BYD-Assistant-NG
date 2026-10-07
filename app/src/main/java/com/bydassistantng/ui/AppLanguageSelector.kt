package com.bydassistantng.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bydassistantng.R
import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.util.AppLanguageChoice

/** The activity behind a Compose [Context] (which may be wrapped), or null. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * The "App language" control: system default, English, or Arabic. The language names for English and
 * Arabic are shown in their own language on purpose, so someone who can't read the current UI language can
 * still find theirs.
 */
@Composable
fun AppLanguageSelector(selected: AppLanguageChoice, onSelect: (AppLanguageChoice) -> Unit) {
    Column {
        Text(stringResource(R.string.app_language_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.app_language_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val options = listOf(
                AppLanguageChoice.SYSTEM to stringResource(R.string.app_language_system),
                AppLanguageChoice.ENGLISH to stringResource(R.string.app_language_english),
                AppLanguageChoice.ARABIC to stringResource(R.string.app_language_arabic),
            )
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) { Text(label) }
            }
        }
    }
}

/** The "Assistant language" control: what the assistant answers in (not the app's own screens). */
@Composable
fun AssistantLanguageSelector(selected: AssistantLanguage, onSelect: (AssistantLanguage) -> Unit) {
    Column {
        Text(stringResource(R.string.assistant_language_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.assistant_language_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val options = AssistantLanguage.entries
            options.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) { Text(value.label()) }
            }
        }
    }
}

@Composable
fun AssistantLanguage.label(): String = stringResource(
    when (this) {
        AssistantLanguage.AUTO -> R.string.language_auto
        AssistantLanguage.ENGLISH -> R.string.language_english
        AssistantLanguage.ARABIC -> R.string.language_arabic
    },
)

@Composable
fun ArabicDialect.label(): String = stringResource(
    when (this) {
        ArabicDialect.MATCH -> R.string.dialect_match
        ArabicDialect.GULF -> R.string.dialect_gulf
        ArabicDialect.EGYPTIAN -> R.string.dialect_egyptian
        ArabicDialect.LEVANTINE -> R.string.dialect_levantine
        ArabicDialect.MAGHREBI -> R.string.dialect_maghrebi
        ArabicDialect.MODERN_STANDARD -> R.string.dialect_msa
    },
)

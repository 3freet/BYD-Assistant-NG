package com.bydassistantng.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydassistantng.R
import com.bydassistantng.ui.AppTopBar
import com.bydassistantng.ui.AppAlertDialog

/*
 * The layout of the settings pages, kept in one place: rounded cards under small section labels, rows made of
 * a title and a muted line with a switch or a pill button on the far side. Two columns where there is room
 * (the head unit), one where there is not. The colours come from the app's theme.
 */

/** Below this width the two columns become one. */
private val TWO_COLUMNS_FROM = 840.dp

/**
 * A settings page: a back button and title, then [left] and [right] side by side (stacked on a narrow
 * screen), all scrolling together. Each is a sequence of [SettingsSection]s.
 */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title, onBack) },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= TWO_COLUMNS_FROM
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            ) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp), content = left)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp), content = right)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        left()
                        right()
                    }
                }
            }
        }
    }
}

/** What goes inside a card: rows, which are drawn with a hairline between them. */
class SettingsCardScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** A small label over a rounded card of rows. A section with no rows is not drawn. */
@Composable
fun SettingsSection(title: String, build: SettingsCardScope.() -> Unit) {
    val scope = SettingsCardScope().apply(build)
    if (scope.rows.isEmpty()) return
    Column {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                scope.rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    row()
                }
            }
        }
    }
}

/**
 * One setting: a [title] over a muted [subtitle] (and a [note] in the warning colour), with something on the
 * far side ([trailing]: a switch or a button) and, if needed, more underneath ([below]: a slider, buttons).
 * With an [onClick] the whole row is pressable.
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    note: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (note != null) {
                    Text(
                        note,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                trailing()
            }
        }
        if (below != null) {
            Column(modifier = Modifier.padding(top = 10.dp), content = below)
        }
    }
}

/** A row that is only a paragraph, in the warning colour: a disclaimer that belongs to the rows around it. */
@Composable
fun SettingNote(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

/** A pill button: the quiet one for most things, the accent one for the thing to do next. */
@Composable
fun SettingButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    dropdown: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = if (primary) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer)
        },
        contentPadding = PaddingValues(start = 22.dp, end = if (dropdown) 12.dp else 22.dp, top = 10.dp, bottom = 10.dp),
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (dropdown) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
}

/** The switch of a row, a little larger than the stock one to be easy to hit in a car. */
@Composable
fun SettingSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = colors.onSurfaceVariant,
            uncheckedTrackColor = colors.outline,
            uncheckedBorderColor = Color.Transparent,
        ),
        modifier = Modifier.scale(1.15f),
    )
}

/** A row with a switch on the far side. The whole row toggles it. */
@Composable
fun SwitchSetting(
    title: String,
    subtitle: String? = null,
    note: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        note = note,
        onClick = { onCheckedChange(!checked) },
        trailing = { SettingSwitch(checked, onCheckedChange) },
        below = below,
    )
}

/** A row whose value is picked from a list: the button on the far side shows the current value. */
@Composable
fun ChoiceSetting(title: String, subtitle: String?, value: String, onClick: () -> Unit) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = { SettingButton(label = value, onClick = onClick, dropdown = true) },
    )
}

/** One entry of a [ChoiceDialog]. */
data class ChoiceOption(val key: String, val label: String, val description: String? = null)

@Composable
fun ChoiceDialog(
    title: String,
    options: List<ChoiceOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(options, key = { it.key }) { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(option.key) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.key == selectedKey, onClick = { onSelect(option.key) })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            option.description?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

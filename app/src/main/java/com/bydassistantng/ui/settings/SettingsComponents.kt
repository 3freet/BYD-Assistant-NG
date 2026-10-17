package com.bydassistantng.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydassistantng.R
import com.bydassistantng.ui.AppAlertDialog
import kotlin.math.roundToInt

/*
 * The building blocks of the settings pages, kept in one place: rounded cards under small group labels, rows made of
 * a title and a muted line with a switch or a pill button on the far side, and the few controls that suit a car
 * (a segmented choice, a stepper with big buttons). The colours come from the app's theme.
 */

/** What needs the user's eye (a setting that is not done yet): amber, the one colour the theme does not have. */
internal val AttentionColor = Color(0xFFF0B45A)

/** What goes inside a card: rows, which are drawn with a hairline between them. */
class SettingsCardScope {
    internal class Row(val nested: Boolean, val content: @Composable () -> Unit)

    internal val rows = mutableListOf<Row>()

    /** A row of the card. A [nested] one belongs to the row above it (an option that only exists while that is on). */
    fun row(nested: Boolean = false, content: @Composable () -> Unit) {
        rows += Row(nested, content)
    }
}

/** A small label over a rounded card of rows. A group with no rows is not drawn; a blank label draws no label. */
@Composable
fun SettingsSection(title: String, build: SettingsCardScope.() -> Unit) {
    val scope = SettingsCardScope().apply(build)
    if (scope.rows.isEmpty()) return
    Column {
        if (title.isNotBlank()) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                scope.rows.forEachIndexed { index, row ->
                    if (index > 0 && !row.nested) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    if (row.nested) {
                        NestedRow(row.content)
                    } else {
                        row.content()
                    }
                }
            }
        }
    }
}

/** An option of the row above it: set in a little, with a line down its left edge. */
@Composable
private fun NestedRow(content: @Composable () -> Unit) {
    val line = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 20.dp, end = 12.dp, bottom = 6.dp)) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(line, RoundedCornerShape(2.dp)))
        Box(modifier = Modifier.weight(1f)) { content() }
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

/** How a [StatusChip] is coloured: done, needs a look, or just a state. */
enum class ChipKind { OK, ATTENTION, MUTED }

/** A short state in a little pill: "Saved", "Up to date", "Off". */
@Composable
fun StatusChip(text: String, kind: ChipKind = ChipKind.MUTED) {
    val colors = MaterialTheme.colorScheme
    val (background, content) = when (kind) {
        ChipKind.OK -> colors.primary.copy(alpha = 0.16f) to colors.primary
        ChipKind.ATTENTION -> AttentionColor.copy(alpha = 0.16f) to AttentionColor
        ChipKind.MUTED -> colors.secondaryContainer to colors.onSurfaceVariant
    }
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = content,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** One choice out of a few, all in view: the chosen one is raised. For a handful of short options, in place of a dialog. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.background)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) colors.surfaceVariant else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) colors.onSurface else colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * A whole number in a range, set two ways: a slider to drag to a place, and a big button for each way to nudge it one
 * [step] at a time, which is surer in a moving car than aiming a thumb. The two always agree; a drag shows its value
 * as it goes and is saved when it is let go (not at every pixel), a button press is saved at once.
 */
@Composable
fun StepperSetting(
    title: String,
    subtitle: String?,
    value: Int,
    range: IntRange,
    step: Int,
    valueText: @Composable (Int) -> String,
    onValueChange: (Int) -> Unit,
    resetLabel: String? = null,
    onReset: (() -> Unit)? = null,
) {
    // What the thumb is at while it is being dragged; null otherwise (the saved value then).
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: value
    SettingRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StepButton(isMore = false, onClick = if (shown > range.first) ({ onValueChange((shown - step).coerceAtLeast(range.first)) }) else null)
                Text(
                    valueText(shown),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 76.dp),
                )
                StepButton(isMore = true, onClick = if (shown < range.last) ({ onValueChange((shown + step).coerceAtMost(range.last)) }) else null)
            }
        },
        below = {
            Slider(
                value = shown.toFloat(),
                onValueChange = { dragging = it.roundToInt() },
                onValueChangeFinished = {
                    dragging?.let { if (it != value) onValueChange(it) }
                    dragging = null
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            )
            if (onReset != null && resetLabel != null) {
                TextButton(onClick = onReset) { Text(resetLabel) }
            }
        },
    )
}

@Composable
private fun StepButton(isMore: Boolean, onClick: (() -> Unit)?) {
    FilledTonalIconButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = Modifier.size(52.dp)) {
        Icon(
            if (isMore) Icons.Filled.Add else Icons.Filled.Remove,
            contentDescription = stringResource(if (isMore) R.string.step_more else R.string.step_less),
        )
    }
}

/** Something to do first, at the top of a page: an amber banner with the action beside it. */
@Composable
fun NoticeBanner(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AttentionColor.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = AttentionColor)
        Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = AttentionColor, modifier = Modifier.weight(1f))
        if (actionLabel != null) SettingButton(label = actionLabel, primary = true, onClick = onAction)
    }
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

package com.bydassistantng.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * One look for the whole app: a near-black page, slightly lighter rounded surfaces, quiet tonal buttons and a
 * single teal accent. It is a fixed palette, not the system's dynamic colours, so the app looks the same on
 * every head unit and next to the user's other apps.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFF35D1BC),
    onPrimary = Color(0xFF00150B),
    primaryContainer = Color(0xFF0F4F46),
    onPrimaryContainer = Color(0xFFB6F5EA),
    secondaryContainer = Color(0xFF252D33),
    onSecondaryContainer = Color(0xFFEFF7FE),
    background = Color(0xFF0C0E10),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0C0E10),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF252D33),
    onSurfaceVariant = Color(0xFFB9BFC6),
    surfaceContainer = Color(0xFF161C21),
    surfaceContainerHigh = Color(0xFF1C242A),
    surfaceContainerHighest = Color(0xFF252D33),
    outline = Color(0xFF39434D),
    outlineVariant = Color(0xFF1E2429),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E8F7D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBFF0E6),
    onPrimaryContainer = Color(0xFF00201B),
    secondaryContainer = Color(0xFFE4EBEE),
    onSecondaryContainer = Color(0xFF11181C),
    background = Color(0xFFF2F5F6),
    onBackground = Color(0xFF11181C),
    surface = Color(0xFFF2F5F6),
    onSurface = Color(0xFF11181C),
    surfaceVariant = Color(0xFFE4EBEE),
    onSurfaceVariant = Color(0xFF59656D),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE4EBEE),
    outline = Color(0xFFB4C0C6),
    outlineVariant = Color(0xFFE2E8EB),
    error = Color(0xFFB3261E),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

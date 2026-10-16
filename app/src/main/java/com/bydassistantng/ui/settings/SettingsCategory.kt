package com.bydassistantng.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.ui.graphics.vector.ImageVector
import com.bydassistantng.R

/**
 * The groups the settings are sorted into: one entry in the list on the left (or one page on a narrow screen),
 * each with the settings that belong together. Which one is open is kept by the screen's owner, so that coming
 * back from the Updates or About screens finds the same group open.
 */
enum class SettingsCategory(@StringRes val title: Int, @StringRes val summary: Int, val icon: ImageVector) {
    ASSISTANT(R.string.settings_section_assistant, R.string.cat_assistant_desc, Icons.Outlined.AutoAwesome),
    CONVERSATION(R.string.settings_section_conversation, R.string.cat_conversation_desc, Icons.Outlined.Mic),
    MEMORY(R.string.settings_section_memory, R.string.cat_memory_desc, Icons.Outlined.Psychology),
    SOUND(R.string.cat_sound, R.string.cat_sound_desc, Icons.AutoMirrored.Outlined.VolumeUp),
    CAR(R.string.settings_section_car, R.string.cat_car_desc, Icons.Outlined.DirectionsCar),
    APP(R.string.settings_section_app, R.string.cat_app_desc, Icons.Outlined.Apps),
}

/** One setting the search can find: its title, and the group it is in. */
internal data class SearchEntry(val category: SettingsCategory, @StringRes val title: Int)

/** Whether [text] answers [query]: any part of it, in any case. An empty query matches nothing. */
internal fun matchesQuery(query: String, text: String): Boolean {
    val wanted = query.trim()
    return wanted.isNotEmpty() && text.contains(wanted, ignoreCase = true)
}

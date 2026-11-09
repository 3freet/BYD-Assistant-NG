package com.bydassistantng.gemini

import android.content.Context
import com.bydassistantng.R
import com.bydassistantng.util.AppLanguage

/**
 * Where in Settings each [SwitchableFeature] is switched on, written with the names the screens carry **in the app's language**
 * (which is not always the language the assistant replies in), so that what the assistant tells the user is what they will read.
 *
 * It follows the Settings screens: each switch is in the section named here (see `SettingsScreen` and `SettingsPanesCar`). When a
 * switch moves, this moves with it.
 */
object SettingsPaths {
    private const val SEPARATOR = " > "

    /** The parts of a path, in order, as one line. */
    fun compose(vararg parts: String): String = parts.joinToString(SEPARATOR)

    fun of(context: Context, feature: SwitchableFeature): String {
        fun text(id: Int) = AppLanguage.string(context, id)
        val settings = text(R.string.settings_title)
        return when (feature) {
            SwitchableFeature.VEHICLE_CONTROL ->
                compose(settings, text(R.string.settings_section_car), text(R.string.vehicle_title))
            SwitchableFeature.EXTERIOR_SPEAKER ->
                compose(settings, text(R.string.settings_section_car), text(R.string.advanced_experimental_title), text(R.string.advanced_exterior_enable_title))
            SwitchableFeature.DRIVE_MODES ->
                compose(settings, text(R.string.settings_section_car), text(R.string.advanced_experimental_title), text(R.string.advanced_drive_modes_title))
            SwitchableFeature.CLUSTER_SCREEN ->
                compose(settings, text(R.string.settings_section_car), text(R.string.advanced_experimental_title), text(R.string.advanced_cluster_enable_title))
            SwitchableFeature.WEB_SEARCH ->
                compose(settings, text(R.string.settings_section_assistant), text(R.string.web_search_title))
            SwitchableFeature.LONG_TERM_MEMORY ->
                compose(settings, text(R.string.settings_section_memory), text(R.string.memory_long_title))
        }
    }
}

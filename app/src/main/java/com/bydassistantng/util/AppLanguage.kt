package com.bydassistantng.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import java.util.Locale

/** What the user picked for the app's own screens and messages. [SYSTEM] follows the device. */
enum class AppLanguageChoice(val tag: String?) { SYSTEM(null), ENGLISH("en"), ARABIC("ar") }

/**
 * The language of the app's UI (screens, banner, notifications) — separate from the language the
 * assistant *speaks*, which is its own setting.
 *
 * Done with a context wrapper rather than the platform's per-app-language API so it behaves the same on
 * every Android version the app supports and also reaches the accessibility service (which hosts the status
 * banner) and notifications. The choice lives in plain SharedPreferences, not DataStore, because it has to
 * be read synchronously while a context is still being created.
 *
 * Wrap a context from `attachBaseContext`; for text produced outside a screen (a service, a view model), use
 * [string], which always reads the current choice — a long-lived context would otherwise keep the language
 * it was created with.
 */
object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY_CHOICE = "choice"

    fun choice(context: Context): AppLanguageChoice {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHOICE, null)
        return AppLanguageChoice.entries.find { it.name == saved } ?: AppLanguageChoice.SYSTEM
    }

    fun setChoice(context: Context, choice: AppLanguageChoice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHOICE, choice.name).apply()
    }

    /** The locale the UI uses right now: the chosen one, or the device's. */
    fun locale(context: Context): Locale =
        choice(context).tag?.let { Locale(it) } ?: systemLocale()

    fun isArabic(context: Context): Boolean = locale(context).language == "ar"

    /** [base] with the app language applied, including its layout direction (right-to-left for Arabic). */
    fun wrap(base: Context): Context {
        val locale = locale(base)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    /** A string in the app language as it is *now*, for code that has no screen to read it from. */
    fun string(context: Context, @StringRes id: Int, vararg args: Any): String =
        wrap(context.applicationContext ?: context).getString(id, *args)

    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]
}

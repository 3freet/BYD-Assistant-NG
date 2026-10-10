package com.bydassistantng.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bydassistantng.util.WheelKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AssistantLanguage(val bcp47: String) { AUTO(""), ENGLISH("en"), ARABIC("ar") }

/** Which Arabic the assistant should speak. [MATCH] says nothing, so the model follows the user. */
enum class ArabicDialect(val promptName: String?) {
    MATCH(null),
    GULF("Gulf Arabic"),
    EGYPTIAN("Egyptian Arabic"),
    LEVANTINE("Levantine Arabic"),
    MAGHREBI("Maghrebi Arabic (North African)"),
    MODERN_STANDARD("Modern Standard Arabic"),
}

// internal, not private: SecureCredentials shares this exact DataStore instance/file — a second
// preferencesDataStore delegate for the same file name would crash at runtime ("multiple
// DataStores active for the same file"), so this must be the one and only declaration of it.
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "assistant_prefs")

/**
 * DataStore-backed replacement for the old app's raw `SharedPreferences` — modern, async-first,
 * type-safe. No "operation mode" here: this app only ever talks to Gemini directly, so the old
 * dual-mode (launch an external assistant app vs. in-house) distinction doesn't exist.
 */
@Singleton
class PreferencesRepository @Inject constructor(@ApplicationContext context: Context) {
    private val dataStore = context.dataStore

    private object Keys {
        val ASSISTANT_LANGUAGE = stringPreferencesKey("assistant_language")
        val VEHICLE_CONTROL_ENABLED = booleanPreferencesKey("vehicle_control_enabled")
        val TRIGGER_KEY_CODE = intPreferencesKey("trigger_key_code")
        val BARGE_IN_ENABLED = booleanPreferencesKey("barge_in_enabled")
        val ARABIC_DIALECT = stringPreferencesKey("arabic_dialect")
        val ASSISTANT_VOICE = stringPreferencesKey("assistant_voice")
        val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val AUTO_START_VISITED_AT = longPreferencesKey("auto_start_visited_at")
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val NOTIFIED_UPDATE_TAG = stringPreferencesKey("notified_update_tag")
        val PENDING_INSTALL = stringPreferencesKey("pending_install_code")
    }

    val assistantLanguage: Flow<AssistantLanguage> = dataStore.data.map { prefs ->
        AssistantLanguage.entries.find { it.name == prefs[Keys.ASSISTANT_LANGUAGE] } ?: AssistantLanguage.AUTO
    }
    suspend fun setAssistantLanguage(language: AssistantLanguage) {
        dataStore.edit { it[Keys.ASSISTANT_LANGUAGE] = language.name }
    }

    val arabicDialect: Flow<ArabicDialect> = dataStore.data.map { prefs ->
        ArabicDialect.entries.find { it.name == prefs[Keys.ARABIC_DIALECT] } ?: ArabicDialect.MATCH
    }
    suspend fun setArabicDialect(dialect: ArabicDialect) {
        dataStore.edit { it[Keys.ARABIC_DIALECT] = dialect.name }
    }

    /** The name of one of Gemini's prebuilt voices, or empty for the server's default. */
    val assistantVoice: Flow<String> = dataStore.data.map { it[Keys.ASSISTANT_VOICE] ?: "" }
    suspend fun setAssistantVoice(voiceName: String) {
        dataStore.edit { it[Keys.ASSISTANT_VOICE] = voiceName }
    }

    /** Lets the assistant look things up on Google when asked something it can't answer from memory
     * (news, weather, sports, prices). Off by default: Google only allows search grounding on a paid
     * Gemini plan, and on a free key the server refuses the session outright (quota error) until the
     * controller retries without it. */
    val webSearchEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.WEB_SEARCH_ENABLED] ?: false }
    suspend fun setWebSearchEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WEB_SEARCH_ENABLED] = enabled }
    }

    /** Off by default — the ported HAL invocation code isn't confirmed safe on real hardware yet,
     * same cautious posture as the project this was ported from. */
    val vehicleControlEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.VEHICLE_CONTROL_ENABLED] ?: false }
    suspend fun setVehicleControlEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VEHICLE_CONTROL_ENABLED] = enabled }
    }

    /** Hardware key code that starts/stops a voice turn — the steering-wheel mic button by default. */
    val triggerKeyCode: Flow<Int> = dataStore.data.map { it[Keys.TRIGGER_KEY_CODE] ?: WheelKeys.MIC_SHORT_PRESS }
    suspend fun setTriggerKeyCode(keyCode: Int) {
        dataStore.edit { it[Keys.TRIGGER_KEY_CODE] = keyCode }
    }

    /** Whether the user can talk over the assistant to interrupt it. On by default; off makes the mic
     * wait until the assistant has finished each reply. */
    val bargeInEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.BARGE_IN_ENABLED] ?: true }
    suspend fun setBargeInEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BARGE_IN_ENABLED] = enabled }
    }

    val onboardingCompleted: Flow<Boolean> = dataStore.data.map { it[Keys.ONBOARDING_COMPLETED] ?: false }
    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }
    suspend fun isOnboardingCompleted(): Boolean = onboardingCompleted.first()

    /** When the user last opened the DiLink auto-start screen (0 = never). The OS exposes no way to
     * read the whitelist itself, so "visited after the latest app update" is the best available
     * stand-in — an update resets whatever the head unit remembered about this app. */
    val autoStartVisitedAt: Flow<Long> = dataStore.data.map { it[Keys.AUTO_START_VISITED_AT] ?: 0L }
    suspend fun markAutoStartVisited(time: Long = System.currentTimeMillis()) {
        dataStore.edit { it[Keys.AUTO_START_VISITED_AT] = time }
    }

    /** The update channel's id (see UpdateChannel), or null until the user picks one, which means "the channel this build came from". */
    val updateChannel: Flow<String?> = dataStore.data.map { it[Keys.UPDATE_CHANNEL] }
    suspend fun setUpdateChannel(id: String) {
        dataStore.edit { it[Keys.UPDATE_CHANNEL] = id }
    }

    /** Whether the app looks for a new version by itself now and then. On by default; nothing is ever installed without a tap. */
    val autoCheckUpdates: Flow<Boolean> = dataStore.data.map { it[Keys.AUTO_CHECK_UPDATES] ?: true }
    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_CHECK_UPDATES] = enabled }
    }

    val lastUpdateCheck: Flow<Long> = dataStore.data.map { it[Keys.LAST_UPDATE_CHECK] ?: 0L }
    suspend fun setLastUpdateCheck(time: Long) {
        dataStore.edit { it[Keys.LAST_UPDATE_CHECK] = time }
    }

    /** The release the "update available" notification was last shown for, so each one is announced once. */
    val notifiedUpdateTag: Flow<String> = dataStore.data.map { it[Keys.NOTIFIED_UPDATE_TAG] ?: "" }
    suspend fun setNotifiedUpdateTag(tag: String) {
        dataStore.edit { it[Keys.NOTIFIED_UPDATE_TAG] = tag }
    }

    /** The version code an install is under way for, so the next start can tell that it went through. Empty when none. */
    val pendingInstall: Flow<String> = dataStore.data.map { it[Keys.PENDING_INSTALL] ?: "" }
    suspend fun setPendingInstall(versionCode: String) {
        dataStore.edit { it[Keys.PENDING_INSTALL] = versionCode }
    }
}

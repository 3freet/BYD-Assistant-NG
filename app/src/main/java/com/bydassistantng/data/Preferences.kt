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
import com.bydassistantng.audio.EndOfSpeechWait
import com.bydassistantng.audio.ExteriorVolume
import com.bydassistantng.audio.MusicDuck
import com.bydassistantng.audio.VoiceChannelOptions
import com.bydassistantng.audio.VoiceSensitivity
import com.bydassistantng.audio.VoiceSpeaker
import com.bydassistantng.util.WheelKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AssistantLanguage(val bcp47: String) { AUTO(""), ENGLISH("en"), ARABIC("ar"), RUSSIAN("ru") }

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
        val EXTERIOR_VOLUME = stringPreferencesKey("exterior_volume")
        val END_OF_SPEECH_WAIT_MS = intPreferencesKey("end_of_speech_wait_ms")
        val SHORT_TERM_MEMORY = booleanPreferencesKey("short_term_memory_enabled")
        val LONG_TERM_MEMORY = booleanPreferencesKey("long_term_memory_enabled")
        val EXTERIOR_SPEAKER_ENABLED = booleanPreferencesKey("exterior_speaker_enabled")
        val DRIVE_MODES_ENABLED = booleanPreferencesKey("drive_modes_enabled")
        val CLUSTER_SCREEN_ENABLED = booleanPreferencesKey("cluster_screen_enabled")
        val CLUSTER_KEEP_MAPS_DISABLED = booleanPreferencesKey("cluster_keep_maps_disabled")
        val VOICE_CHANNEL_ENABLED = booleanPreferencesKey("voice_channel_enabled")
        val VOICE_CHANNEL_MUSIC = stringPreferencesKey("voice_channel_music")
        val VOICE_CHANNEL_ADAPTIVE = booleanPreferencesKey("voice_channel_adaptive")
        val VOICE_CHANNEL_GAIN = intPreferencesKey("voice_channel_gain")
        val VOICE_CHANNEL_DIP = intPreferencesKey("voice_channel_dip")
        val VOICE_CHANNEL_DIP_ALWAYS = booleanPreferencesKey("voice_channel_dip_always")
        val VOICE_CHANNEL_SPEAKER = stringPreferencesKey("voice_channel_speaker")
        val HEADREST_SPEAKER_PRESENT = booleanPreferencesKey("headrest_speaker_present")
        val GREETING_ENABLED = booleanPreferencesKey("greeting_enabled")
        val GREETING_SPEAKER = stringPreferencesKey("greeting_speaker")
        val VOICE_SENSITIVITY = intPreferencesKey("voice_sensitivity")
        val LEVEL_GATE_ENABLED = booleanPreferencesKey("level_gate_enabled")
        val MUSIC_HINT_ENABLED = booleanPreferencesKey("music_hint_enabled")
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

    /** Whether the assistant may operate the car's controls (windows, seats, climate...), not just talk about
     * them. On by default: it is what the assistant is for. Someone who switched it off keeps it off. */
    val vehicleControlEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.VEHICLE_CONTROL_ENABLED] ?: CoreFeatures.VEHICLE_CONTROL_DEFAULT }
    suspend fun setVehicleControlEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VEHICLE_CONTROL_ENABLED] = enabled }
    }

    /** Hardware key code that starts/stops a voice turn — the steering-wheel mic button by default. */
    val triggerKeyCode: Flow<Int> = dataStore.data.map { it[Keys.TRIGGER_KEY_CODE] ?: WheelKeys.MIC_SHORT_PRESS }
    suspend fun setTriggerKeyCode(keyCode: Int) {
        dataStore.edit { it[Keys.TRIGGER_KEY_CODE] = keyCode }
    }

    /**
     * Whether the assistant says hello by itself when the car has woken, the driver has sat down and the driver's door has
     * shut. Off by default: it is the one thing the assistant does without being asked.
     */
    val greetingEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.GREETING_ENABLED] ?: false }
    suspend fun setGreetingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.GREETING_ENABLED] = enabled }
    }

    /**
     * Which speakers the greeting is heard from. Its own setting, apart from the assistant's usual voice: the driver's
     * headrest by default, so that the greeting is for the driver alone (it falls back to the car's speakers on a car
     * that has no headrest speaker, or with the voice channel off).
     */
    val greetingSpeaker: Flow<VoiceSpeaker> =
        dataStore.data.map { prefs -> prefs[Keys.GREETING_SPEAKER]?.let { VoiceSpeaker.from(it) } ?: VoiceSpeaker.HEADREST }
    suspend fun setGreetingSpeaker(speaker: VoiceSpeaker) {
        dataStore.edit { it[Keys.GREETING_SPEAKER] = speaker.key }
    }

    /** How easily a sound is taken for the user speaking, in steps around 0 (see [VoiceSensitivity]). */
    val voiceSensitivity: Flow<Int> = dataStore.data.map { VoiceSensitivity.clamp(it[Keys.VOICE_SENSITIVITY] ?: VoiceSensitivity.DEFAULT) }
    suspend fun setVoiceSensitivity(step: Int) {
        dataStore.edit { it[Keys.VOICE_SENSITIVITY] = VoiceSensitivity.clamp(step) }
    }

    /**
     * Whether, while music is playing, only what is clearly louder than the music and the cabin is sent to the server, so
     * that sung words are not taken for the user (see [com.bydassistantng.audio.LevelGate]). Off by default: it has to be
     * tuned on a drive, and a user who is not clearly louder than the music is cut.
     */
    val levelGateEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.LEVEL_GATE_ENABLED] ?: false }
    suspend fun setLevelGateEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.LEVEL_GATE_ENABLED] = enabled }
    }

    /** Whether the assistant's instructions say that music may be playing and that sung words are not the user. Off by default. */
    val musicHintEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.MUSIC_HINT_ENABLED] ?: false }
    suspend fun setMusicHintEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.MUSIC_HINT_ENABLED] = enabled }
    }

    /** Whether the user can talk over the assistant to interrupt it. On by default; off makes the mic
     * wait until the assistant has finished each reply. */
    val bargeInEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.BARGE_IN_ENABLED] ?: true }
    suspend fun setBargeInEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BARGE_IN_ENABLED] = enabled }
    }

    /**
     * Whether the last few exchanges are carried into the next conversation (for ten minutes), so that a follow-up
     * works. On by default; nothing leaves the device but the conversation itself.
     */
    val shortTermMemoryEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.SHORT_TERM_MEMORY] ?: true }
    suspend fun setShortTermMemoryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SHORT_TERM_MEMORY] = enabled }
    }

    /** Whether the assistant keeps what it is asked to remember and uses it in later conversations. */
    val longTermMemoryEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.LONG_TERM_MEMORY] ?: true }
    suspend fun setLongTermMemoryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.LONG_TERM_MEMORY] = enabled }
    }

    /** How long the assistant waits in silence after the user's speech before deciding they have finished (milliseconds). */
    val endOfSpeechWaitMs: Flow<Int> = dataStore.data.map { prefs ->
        EndOfSpeechWait.clamp(prefs[Keys.END_OF_SPEECH_WAIT_MS] ?: EndOfSpeechWait.DEFAULT_MS)
    }
    suspend fun setEndOfSpeechWaitMs(ms: Int) {
        dataStore.edit { it[Keys.END_OF_SPEECH_WAIT_MS] = EndOfSpeechWait.clamp(ms) }
    }

    /**
     * Whether the assistant may use the car's exterior speaker at all (speaking and sound effects outside the
     * car). Off by default; when off nothing of it is offered, started or run. Only has an effect in a build
     * that has the exterior speaker.
     */
    val exteriorSpeakerEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.EXTERIOR_SPEAKER_ENABLED] ?: OptInFeatures.EXTERIOR_SPEAKER_DEFAULT }
    suspend fun setExteriorSpeakerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.EXTERIOR_SPEAKER_ENABLED] = enabled }
    }

    /**
     * Whether the assistant may change the car's drive mode (sand, mud, snow, eco, sport...). Off by default;
     * when off the tool is not offered and a request is refused. Only has an effect in a build that has it.
     */
    val driveModesEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.DRIVE_MODES_ENABLED] ?: OptInFeatures.DRIVE_MODES_DEFAULT }
    suspend fun setDriveModesEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DRIVE_MODES_ENABLED] = enabled }
    }

    /**
     * Whether the assistant may put an app on the car's instrument cluster (an experimental feature). Off by
     * default; when off the tool is not offered and a request is refused.
     */
    val clusterScreenEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.CLUSTER_SCREEN_ENABLED] ?: OptInFeatures.CLUSTER_SCREEN_DEFAULT }
    suspend fun setClusterScreenEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CLUSTER_SCREEN_ENABLED] = enabled }
    }

    /**
     * What happens to BYD Maps once the app has left the cluster: on = it is disabled again (once the cluster has
     * its gauges back), off = it stays enabled. The cluster tool always starts from "BYD Maps is disabled", so it
     * enables it first.
     */
    val clusterKeepMapsDisabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.CLUSTER_KEEP_MAPS_DISABLED] ?: OptInFeatures.CLUSTER_KEEP_MAPS_DISABLED_DEFAULT }
    suspend fun setClusterKeepMapsDisabled(disabled: Boolean) {
        dataStore.edit { it[Keys.CLUSTER_KEEP_MAPS_DISABLED] = disabled }
    }

    /** Whether replies are spoken on the car's voice channel, where the car has one. On by default. */
    val voiceChannelEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.VOICE_CHANNEL_ENABLED] ?: CoreFeatures.VOICE_CHANNEL_DEFAULT }
    suspend fun setVoiceChannelEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VOICE_CHANNEL_ENABLED] = enabled }
    }

    /** What happens to the music while a reply is spoken on the voice channel. */
    val voiceChannelMusic: Flow<MusicDuck> =
        dataStore.data.map { MusicDuck.from(it[Keys.VOICE_CHANNEL_MUSIC]) }
    suspend fun setVoiceChannelMusic(music: MusicDuck) {
        dataStore.edit { it[Keys.VOICE_CHANNEL_MUSIC] = music.key }
    }

    /** Whether the voice channel's volume follows how loud the cabin is. On by default. */
    val voiceChannelAdaptive: Flow<Boolean> =
        dataStore.data.map { it[Keys.VOICE_CHANNEL_ADAPTIVE] ?: CoreFeatures.VOICE_CHANNEL_ADAPTIVE_DEFAULT }
    suspend fun setVoiceChannelAdaptive(adaptive: Boolean) {
        dataStore.edit { it[Keys.VOICE_CHANNEL_ADAPTIVE] = adaptive }
    }

    /** How far above (or below) what the cabin noise asks for the voice channel's volume is set, in steps. */
    val voiceChannelGain: Flow<Int> =
        dataStore.data.map { VoiceChannelOptions.clampGain(it[Keys.VOICE_CHANNEL_GAIN] ?: 0) }
    suspend fun setVoiceChannelGain(steps: Int) {
        dataStore.edit { it[Keys.VOICE_CHANNEL_GAIN] = VoiceChannelOptions.clampGain(steps) }
    }

    /** How much the music is turned down while a reply is spoken, in percent, when the music choice is to turn it down by a share. */
    val voiceChannelDip: Flow<Int> =
        dataStore.data.map { VoiceChannelOptions.clampDip(it[Keys.VOICE_CHANNEL_DIP] ?: VoiceChannelOptions.DEFAULT_DIP_PERCENT) }
    suspend fun setVoiceChannelDip(percent: Int) {
        dataStore.edit { it[Keys.VOICE_CHANNEL_DIP] = VoiceChannelOptions.clampDip(percent) }
    }

    /**
     * Whether the media volume is also turned down when nothing is reported as playing. Off by default (as since 16 Oct, when a
     * dip with nothing playing was reported as a bug); a fallback for when the app cannot see what is playing (see
     * [com.bydassistantng.audio.MediaSources]).
     */
    val voiceChannelDipAlways: Flow<Boolean> = dataStore.data.map { it[Keys.VOICE_CHANNEL_DIP_ALWAYS] ?: false }
    suspend fun setVoiceChannelDipAlways(always: Boolean) {
        dataStore.edit { it[Keys.VOICE_CHANNEL_DIP_ALWAYS] = always }
    }

    /** Which speakers the assistant's voice comes out of (the headrest one only works with the voice channel). */
    val voiceChannelSpeaker: Flow<VoiceSpeaker> =
        dataStore.data.map { VoiceSpeaker.from(it[Keys.VOICE_CHANNEL_SPEAKER]) }
    suspend fun setVoiceChannelSpeaker(speaker: VoiceSpeaker) {
        dataStore.edit { it[Keys.VOICE_CHANNEL_SPEAKER] = speaker.key }
    }

    /** Whether the car was found to have a headrest speaker, read once from the car; null while that is not known. */
    val headrestSpeakerPresent: Flow<Boolean?> = dataStore.data.map { it[Keys.HEADREST_SPEAKER_PRESENT] }
    suspend fun setHeadrestSpeakerPresent(present: Boolean) {
        dataStore.edit { it[Keys.HEADREST_SPEAKER_PRESENT] = present }
    }

    /** How loud the assistant is when it speaks or plays a sound on the exterior speaker, unless asked for another level. */
    val exteriorVolume: Flow<ExteriorVolume> = dataStore.data.map { prefs ->
        ExteriorVolume.entries.find { it.name == prefs[Keys.EXTERIOR_VOLUME] } ?: ExteriorVolume.DEFAULT
    }
    suspend fun setExteriorVolume(volume: ExteriorVolume) {
        dataStore.edit { it[Keys.EXTERIOR_VOLUME] = volume.name }
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

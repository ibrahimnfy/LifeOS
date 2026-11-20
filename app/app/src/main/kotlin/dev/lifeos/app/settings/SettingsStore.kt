package dev.lifeos.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.lifeos.app.lock.LockTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App settings kept on the phone. The language is not here: the platform stores it. */
class SettingsStore(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val theme: Flow<ThemeSetting> = dataStore.data
        .map { ThemeSetting.fromStored(it[THEME]) }
        .distinctUntilChanged()

    suspend fun setTheme(setting: ThemeSetting) {
        dataStore.edit { it[THEME] = setting.storedValue }
    }

    val lockTimeout: Flow<LockTimeout> = dataStore.data
        .map { LockTimeout.fromStored(it[LOCK_TIMEOUT]) }
        .distinctUntilChanged()

    suspend fun setLockTimeout(timeout: LockTimeout) {
        dataStore.edit { it[LOCK_TIMEOUT] = timeout.storedValue }
    }

    /** Whether the welcome screen was already shown (or pairing was done). */
    val welcomed: Flow<Boolean> = dataStore.data
        .map { it[WELCOMED] == "1" }
        .distinctUntilChanged()

    suspend fun setWelcomed() {
        dataStore.edit { it[WELCOMED] = "1" }
    }

    /** The coach messages shown most recently (ids only), newest last. */
    val coachRecent: Flow<List<String>> = dataStore.data
        .map { it[COACH_RECENT].orEmpty().split(',').filter(String::isNotEmpty) }
        .distinctUntilChanged()

    suspend fun setCoachRecent(ids: List<String>) {
        dataStore.edit { it[COACH_RECENT] = ids.joinToString(",") }
    }

    /** Whether screen time is sent to the computer once a day (on by default; it needs usage access anyway). */
    val usageSync: Flow<Boolean> = dataStore.data
        .map { it[USAGE_SYNC] != "0" }
        .distinctUntilChanged()

    suspend fun setUsageSync(on: Boolean) {
        dataStore.edit { it[USAGE_SYNC] = if (on) "1" else "0" }
    }

    /** The silent morning question when last night's sleep was not entered (on by default). */
    val sleepPrompt: Flow<Boolean> = dataStore.data
        .map { it[SLEEP_PROMPT] != "0" }
        .distinctUntilChanged()

    suspend fun setSleepPrompt(on: Boolean) {
        dataStore.edit { it[SLEEP_PROMPT] = if (on) "1" else "0" }
    }

    /** Its time of day, `HH:MM` (default 05:00). */
    val sleepPromptTime: Flow<String> = dataStore.data
        .map { it[SLEEP_PROMPT_TIME] ?: "05:00" }
        .distinctUntilChanged()

    suspend fun setSleepPromptTime(time: String) {
        dataStore.edit { it[SLEEP_PROMPT_TIME] = time }
    }

    private companion object {
        val SLEEP_PROMPT = stringPreferencesKey("sleep_prompt")
        val SLEEP_PROMPT_TIME = stringPreferencesKey("sleep_prompt_time")
        val USAGE_SYNC = stringPreferencesKey("usage_sync")
        val COACH_RECENT = stringPreferencesKey("coach_recent")
        val THEME = stringPreferencesKey("theme")
        val LOCK_TIMEOUT = stringPreferencesKey("lock_timeout")
        val WELCOMED = stringPreferencesKey("welcomed")
    }
}

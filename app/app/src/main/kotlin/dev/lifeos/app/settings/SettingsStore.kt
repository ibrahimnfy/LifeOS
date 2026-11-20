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

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val LOCK_TIMEOUT = stringPreferencesKey("lock_timeout")
        val WELCOMED = stringPreferencesKey("welcomed")
    }
}

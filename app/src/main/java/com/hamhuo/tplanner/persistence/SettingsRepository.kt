package com.hamhuo.tplanner.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val SETTINGS_DATA_STORE_NAME = "tplanner_settings"
private const val LEGACY_SYNC_PREFERENCES_NAME = "tplanner_sync_config"
private const val SERVER_URL_NAME = "serverUrl"

private val SERVER_URL_KEY = stringPreferencesKey(SERVER_URL_NAME)

private val Context.tplannerSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_DATA_STORE_NAME,
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(
                context = context,
                sharedPreferencesName = LEGACY_SYNC_PREFERENCES_NAME,
                // Restrict cleanup to this key. In particular, sync_base_* stays in the legacy
                // preferences until the Room sync-shadow migration has been fully rolled out.
                keysToMigrate = setOf(SERVER_URL_NAME),
            )
        )
    },
)

/** Stores small application settings independently from journals and sync state. */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.tplannerSettingsDataStore

    /**
     * Emits the stored value unchanged so callers can decide when to normalize user input. Missing,
     * blank, or temporarily unreadable settings fall back to the production sync endpoint.
     */
    val serverUrl: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            preferences[SERVER_URL_KEY]?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL
        }

    /** Persists an already-normalized URL; blank input resets the effective value to the default. */
    suspend fun setServerUrl(normalizedUrl: String?) {
        val value = normalizedUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL
        dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = value
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://sync.hamhuo.top"
    }
}

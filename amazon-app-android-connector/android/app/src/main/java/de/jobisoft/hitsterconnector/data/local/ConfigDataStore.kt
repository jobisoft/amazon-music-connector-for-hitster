package de.jobisoft.hitsterconnector.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class ConfigDataStore @Inject constructor(
    private val context: Context
) {
    companion object {
        private val CSV_URLS_KEY = stringSetPreferencesKey("csv_urls")
    }

    val csvUrlsFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[CSV_URLS_KEY] ?: emptySet()
    }

    suspend fun saveCSVUrls(urls: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[CSV_URLS_KEY] = urls.filter { it.isNotBlank() }.toSet()
        }
    }

    suspend fun getCsvUrls(): Set<String> {
        return csvUrlsFlow.first()
    }
}

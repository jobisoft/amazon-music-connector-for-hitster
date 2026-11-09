package de.jobisoft.hitsterconnector.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.jobisoft.hitsterconnector.domain.model.ConnectorConfig
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
        private val SERVER_URL_KEY = stringPreferencesKey("connector_server_url")
        private val TOKEN_KEY = stringPreferencesKey("connector_token")
        private val SERVER_ID_KEY = stringPreferencesKey("connector_server_id")
        private val SERVER_NAME_KEY = stringPreferencesKey("connector_server_name")
        private val CSV_URLS_KEY = stringSetPreferencesKey("csv_urls")
    }

    val configFlow: Flow<ConnectorConfig?> = context.dataStore.data.map { preferences ->
        val serverUrl = preferences[SERVER_URL_KEY]
        // The token is empty for a computer that takes requests without pairing.
        val token = preferences[TOKEN_KEY] ?: ""
        if (!serverUrl.isNullOrBlank()) {
            ConnectorConfig(
                serverUrl = serverUrl,
                token = token,
                serverId = preferences[SERVER_ID_KEY],
                serverName = preferences[SERVER_NAME_KEY]
            )
        } else {
            null
        }
    }

    suspend fun saveConfig(config: ConnectorConfig) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = config.serverUrl
            preferences[TOKEN_KEY] = config.token
            config.serverId?.let { preferences[SERVER_ID_KEY] = it } ?: preferences.remove(SERVER_ID_KEY)
            config.serverName?.let { preferences[SERVER_NAME_KEY] = it } ?: preferences.remove(SERVER_NAME_KEY)
        }
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

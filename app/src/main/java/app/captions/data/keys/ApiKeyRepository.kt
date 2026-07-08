package app.captions.data.keys

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ApiProvider(val prefKey: String) {
    OPENROUTER("openrouter_api_key"),
    ELEVENLABS("elevenlabs_api_key"),
}

/** Persists API keys encrypted-at-rest; exposes decrypted values as flows. */
@Singleton
class ApiKeyRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val crypto: KeyCrypto,
) {

    fun key(provider: ApiProvider): Flow<String?> =
        dataStore.data.map { prefs ->
            prefs[stringPreferencesKey(provider.prefKey)]?.let { stored ->
                runCatching { crypto.decrypt(stored) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
        }

    suspend fun setKey(provider: ApiProvider, value: String?) {
        val prefKey = stringPreferencesKey(provider.prefKey)
        dataStore.edit { prefs ->
            val trimmed = value?.trim()
            if (trimmed.isNullOrEmpty()) {
                prefs.remove(prefKey)
            } else {
                prefs[prefKey] = crypto.encrypt(trimmed)
            }
        }
    }
}
